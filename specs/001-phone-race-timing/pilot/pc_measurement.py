import argparse
from array import array
from contextlib import ExitStack
import ctypes as c
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import time
import uuid


def offset_window(markers):
    lower = max(before - desk - 1 for before, desk, after in markers)
    upper = min(after - desk + 1 for before, desk, after in markers)
    return (lower, upper) if lower <= upper else None


def clock_error(mapped_elapsed_us, start_reference, finish_reference):
    if (mapped_elapsed_us <= 0 or start_reference[0] > start_reference[1]
            or finish_reference[0] > finish_reference[1] or finish_reference[0] <= start_reference[1]):
        raise ValueError("Reference markers must be ordered and the mapped interval must be positive.")
    lower = finish_reference[0] - start_reference[1]
    upper = finish_reference[1] - start_reference[0]
    center = (lower + upper) / 2
    uncertainty = (upper - lower) / 2
    error = mapped_elapsed_us - center
    lower_error = max(0, abs(error) - uncertainty)
    upper_error = abs(error) + uncertainty
    result = "within_limit" if upper_error <= 10_000 else "outside_limit" if lower_error > 10_000 else "inconclusive"
    return {"error_us": error, "reference_uncertainty_us": uncertainty, "upper_error_us": upper_error, "result": result}


def signal_onset(windows, threshold):
    for index, (when, peak) in enumerate(windows):
        if peak < threshold:
            continue
        nearby = windows[index:index + 8]
        if sum(peak >= threshold and moment - when < 10_000 for moment, peak in nearby) >= 5:
            return when
    return None


def now_us():
    return time.perf_counter_ns() // 1000


def browser_marker(page):
    samples = []
    for _ in range(12):
        before = now_us()
        desk = page.evaluate("schaetzung ? jetzt() + schaetzung.versatz : null")
        after = now_us()
        if desk is not None:
            samples.append((before, desk, after + 1))
    if not samples:
        raise RuntimeError("The browser must have a clock estimate before measuring a marker.")
    return min(samples, key=lambda sample: sample[2] - sample[0])


def guid(value):
    return c.create_string_buffer(uuid.UUID(value).bytes_le)


def checked(status):
    if status < 0:
        raise OSError(f"WASAPI HRESULT 0x{status & 0xffffffff:08X}")


class Loopback:
    def __init__(self, stack):
        ole = c.OleDLL("ole32")
        ole.CoInitializeEx(None, 0)
        stack.callback(ole.CoUninitialize)
        enumerator = c.c_void_p()
        ole.CoCreateInstance(guid("BCDE0395-E52F-467C-8E3D-C4579291692E"), None, 1,
                             guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), c.byref(enumerator))
        stack.callback(self.call, enumerator, 2, c.c_ulong)
        device = c.c_void_p()
        checked(self.call(enumerator, 4, c.c_long, (c.c_int, 0), (c.c_int, 1), (c.c_void_p, c.byref(device))))
        stack.callback(self.call, device, 2, c.c_ulong)
        client = c.c_void_p()
        checked(self.call(device, 3, c.c_long, (c.c_void_p, guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2")),
                          (c.c_uint, 1), (c.c_void_p, None), (c.c_void_p, c.byref(client))))
        stack.callback(self.call, client, 2, c.c_ulong)
        mix = c.c_void_p()
        checked(self.call(client, 8, c.c_long, (c.c_void_p, c.byref(mix))))
        stack.callback(ole.CoTaskMemFree, mix)
        tag, self.channels, self.rate, _, self.align, bits, extra = struct.unpack("<HHIIHHH", c.string_at(mix, 18))
        if tag == 0xfffe and extra >= 22:
            tag = struct.unpack("<I", c.string_at(mix.value + 24, 4))[0]
        try:
            self.sample_format, self.full_scale = {(3, 32): ("f", 1), (1, 16): ("h", 32768),
                                                   (1, 32): ("i", 2147483648)}[tag, bits]
        except KeyError as ex:
            raise ValueError(f"Unsupported loopback sample format: {tag}, {bits} bits") from ex
        checked(self.call(client, 3, c.c_long, (c.c_int, 0), (c.c_uint, 0x20000),
                          (c.c_int64, 2_000_000), (c.c_int64, 0), (c.c_void_p, mix), (c.c_void_p, None)))
        self.capture = c.c_void_p()
        checked(self.call(client, 14, c.c_long, (c.c_void_p, guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317")),
                          (c.c_void_p, c.byref(self.capture))))
        stack.callback(self.call, self.capture, 2, c.c_ulong)
        checked(self.call(client, 10, c.c_long))
        stack.callback(self.call, client, 11, c.c_long)

    @staticmethod
    def call(interface, index, result_type, *arguments):
        table = c.cast(interface, c.POINTER(c.POINTER(c.c_void_p))).contents
        method = c.WINFUNCTYPE(result_type, c.c_void_p, *(kind for kind, _ in arguments))(table[index])
        return method(interface, *(value for _, value in arguments))

    def windows(self):
        peaks = []
        flags_seen = []
        count = c.c_uint()
        checked(self.call(self.capture, 5, c.c_long, (c.c_void_p, c.byref(count))))
        while count.value:
            data, frames, flags = c.c_void_p(), c.c_uint(), c.c_uint()
            qpc = c.c_uint64()
            checked(self.call(self.capture, 3, c.c_long, (c.c_void_p, c.byref(data)), (c.c_void_p, c.byref(frames)),
                              (c.c_void_p, c.byref(flags)), (c.c_void_p, None), (c.c_void_p, c.byref(qpc))))
            try:
                raw = b"" if flags.value & 2 else c.string_at(data, frames.value * self.align)
            finally:
                checked(self.call(self.capture, 4, c.c_long, (c.c_uint, frames.value)))
            stamp = qpc.value / 10
            if flags.value & 5:
                flags_seen.append((stamp, flags.value))
            if stamp and not flags.value & 4:
                values = array(self.sample_format)
                values.frombytes(raw)
                stride = max(1, self.rate // 1000)
                for begin in range(0, frames.value, stride):
                    window = values[begin * self.channels:(begin + stride) * self.channels]
                    peak = max((abs(value) for value in window), default=0) / self.full_scale
                    peaks.append((stamp + begin * 1_000_000 / self.rate, peak))
            checked(self.call(self.capture, 5, c.c_long, (c.c_void_p, c.byref(count))))
        return peaks, flags_seen


def java_ping(process, command):
    before = now_us()
    process.stdin.write(command + "\n")
    process.stdin.flush()
    value = process.stdout.readline()
    after = now_us()
    if not value:
        raise RuntimeError(f"Java probe stopped with status {process.wait()}")
    return before, int(value), after


def elapsed_digital(result, offset, flagged):
    nominal = signal_onset(result, 0.004)
    lower = signal_onset(result, 0.002)
    upper = signal_onset(result, 0.008)
    if nominal is None or lower is None or upper is None:
        return None
    if any(flags & 4 or lower - 10_000 <= stamp <= upper + 10_000 for stamp, flags in flagged):
        return None
    if upper - lower > 10_000 or upper < offset[0] - 2_000:
        return None
    return [round(lower - 1000 - offset[1], 1), round(upper + 1000 - offset[0], 1)]


def measure(jdk, durations):
    folder = Path(__file__).resolve().parent
    repo = folder.parents[2]
    source = repo / "jauswertung-plugins-input/src/main/java/de/df/jauswertung/gui/plugins/zeitnahme"
    with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
        subprocess.run([jdk / "javac.exe", "-d", directory, source / "Startsignal.java", folder / "PcHornProbe.java"],
                       check=True)
        process = subprocess.Popen([jdk / "java.exe", "-cp", directory,
                                    "de.df.jauswertung.gui.plugins.zeitnahme.PcHornProbe"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        def stop():
            process.stdin.close()
            process.stdout.close()
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=4)
        stack.callback(stop)
        capture = Loopback(stack)
        observations = []
        for duration in durations:
            before = [java_ping(process, "clock") for _ in range(12)]
            capture.windows()
            _, start, _ = java_ping(process, str(duration))
            samples, flags = [], []
            end = now_us() + duration * 1000 + 350_000
            while now_us() < end:
                peaks, status = capture.windows()
                samples.extend(peaks)
                flags.extend(status)
                time.sleep(0.003)
            after = [java_ping(process, "clock") for _ in range(12)]
            offset = offset_window(before + after)
            window = None if offset is None else elapsed_digital(samples, (start + offset[0], start + offset[1]), flags)
            observations.append({"horn_ms": duration, "audio_sample_frames_per_second": capture.rate,
                                 "clock_alignment_width_us": None if offset is None else offset[1] - offset[0],
                                 "digital_onset_minus_desk_start_us": window,
                                 "packets_with_flags": len(flags)})
        return {"measurement": "Windows WASAPI loopback", "acoustic_onset": "not measured", "runs": observations}


def main():
    parser = argparse.ArgumentParser(description="Gather PC digital horn onset or score independent clock markers.")
    parser.add_argument("--jdk-bin", type=Path)
    parser.add_argument("--duration", nargs="+", type=int, default=[200, 400, 1500])
    parser.add_argument("--clock-json", type=Path, help="JSON with mapped_elapsed_us, start_reference, finish_reference")
    args = parser.parse_args()
    if args.clock_json:
        result = clock_error(**json.loads(args.clock_json.read_text(encoding="utf-8")))
    else:
        if any(duration < 200 or duration > 1500 for duration in args.duration):
            parser.error("Durations must be between 200 and 1500 milliseconds.")
        jdk = args.jdk_bin or Path(shutil.which("javac.exe") or "javac.exe").parent
        result = measure(jdk, args.duration)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
