"use strict";

const byId = id => document.getElementById(id);
const jetzt = () => Math.round(performance.now() * 1000);
const warten = ms => new Promise(resolve => setTimeout(resolve, ms));
const epoche = crypto.randomUUID();
const SCHLUESSEL = "jauswertung.zeitnahme";
const uhrAdresse = new URL("/api/uhr", location.href).href;
const ABGLEICH_INTERVALL_MS = 200;
const statusText = {
  BEREIT: "Lauf scharfgeschaltet",
  TISCHSTART: "Start am Wettkampftisch",
  FREIGEGEBEN: "Wartet auf Startmeldung",
  START_UNSICHER: "Wettkampftisch muss den Start bestätigen",
  LAEUFT: "Lauf gestartet",
  GESCHLOSSEN: "Versuch geschlossen",
  UEBERNOMMEN: "Zeiten in den Wettkampf übernommen"
};
const fehlerText = {
  code: "Kopplungscode ungültig oder bereits verwendet.",
  nichtGekoppelt: "Kopplung abgelaufen. Einen neuen Code am Wettkampftisch holen.",
  rolle: "Für dieses Gerät nicht freigegeben.",
  nichtBereit: "Start derzeit nicht freigegeben. Bitte den Uhrabgleich prüfen.",
  veraltet: "Der Lauf wurde geändert oder der Wettkampftisch ist belegt.",
  schaetzung: "Uhrabgleich fehlt oder ist abgelaufen.",
  geschlossen: "Der Versuch ist geschlossen.",
  abgelehnt: "Die Meldung wurde abgelehnt.",
  speicher: "Das Handy konnte die Meldung nicht speichern."
};

let speicher = { zugang: null, meldungen: [] };
let lokalVerfuegbar = true;
try {
  const vorhanden = JSON.parse(sessionStorage.getItem(SCHLUESSEL) || "null");
  if (vorhanden?.meldungen && Array.isArray(vorhanden.meldungen)) speicher = vorhanden;
  sessionStorage.setItem(SCHLUESSEL, JSON.stringify(speicher));
} catch {
  lokalVerfuegbar = false;
}

let lauf;
let schaetzung;
let abgleichFaellig = 0;
let abgleichLaeuft;
let ersteProben = 0;
let audio;
let tonAktiv = false;
let startAktiv = false;
let sendet = false;
let bahnAnsicht = "";

function melden(text) {
  byId("meldung").textContent = text;
}

function schreiben(neu) {
  try {
    sessionStorage.setItem(SCHLUESSEL, JSON.stringify(neu));
    speicher = neu;
    darstellen();
    return true;
  } catch {
    melden(fehlerText.speicher);
    return false;
  }
}

async function api(pfad, daten = {}, kopfEmpfangen) {
  const headers = {"Content-Type": "application/json"};
  if (speicher.zugang) headers.Authorization = "Bearer " + speicher.zugang;
  const antwort = await fetch("/api/" + pfad, {
    method: "POST", headers, body: JSON.stringify(daten), credentials: "omit", cache: "no-store",
    signal: AbortSignal.timeout(4500)
  });
  kopfEmpfangen?.();
  const wert = await antwort.json();
  if (!antwort.ok) {
    throw Object.assign(new Error(wert.fehler), { status: antwort.status, grund: wert.fehler });
  }
  return wert;
}

function meldungSetzen(meldung) {
  const rest = speicher.meldungen.filter(eintrag => eintrag.id !== meldung.id);
  return schreiben({ ...speicher, meldungen: [...rest, meldung] });
}

async function senden() {
  if (sendet || startAktiv || !speicher.zugang) return;
  sendet = true;
  try {
    for (const meldung of speicher.meldungen.filter(eintrag => eintrag.status === "Ausstehend")) {
      try {
        await api(meldung.pfad, meldung.daten);
        meldungSetzen({ ...meldung, status: "Angenommen" });
      } catch (error) {
        if (!error.status || error.status >= 500) break;
        meldungSetzen({ ...meldung, status: "Abgelehnt", grund: error.grund });
      }
    }
  } finally {
    sendet = false;
  }
}

function statusZu(laufId, bezeichnung) {
  return speicher.meldungen.find(eintrag => eintrag.daten.versuch === laufId
    && eintrag.bezeichnung === bezeichnung && eintrag.status !== "Abgelehnt");
}

function darstellen() {
  byId("paarung").hidden = !!speicher.zugang;
  byId("lauf").hidden = !lauf?.versuch && !lauf?.starter;
  byId("starter").hidden = !lauf?.starter;
  byId("disziplin").textContent = lauf?.disziplin || "";
  byId("start").disabled = !lauf?.bereit || !schaetzung || tonAktiv || startAktiv || !!statusZu(lauf?.versuch, "Start");
  byId("start").textContent = lauf?.countdown ? "Countdown starten" : "Startsignal auslösen";
  byId("testton").disabled = !lauf?.starter || !!lauf?.versuch || tonAktiv || startAktiv;
  const aktuell = [...(lauf?.bahnen || [])];
  const ansicht = JSON.stringify([lauf?.versuch, aktuell]);
  const bahnen = byId("bahnen");
  if (ansicht !== bahnAnsicht) {
    bahnAnsicht = ansicht;
    bahnen.replaceChildren(...aktuell.map(bahn => {
      const karte = document.createElement("article");
      const titel = document.createElement("h3");
      titel.textContent = "Bahn " + bahn.nummer + " · " + bahn.teilnehmer;
      const taste = document.createElement("button");
      taste.type = "button";
      taste.textContent = bahn.telefonzeit ? "Ziel erfassen" : "Stoppuhrzeit senden";
      let feld;
      karte.append(titel);
      if (!bahn.telefonzeit) {
        const label = document.createElement("label");
        label.textContent = "Stoppuhrzeit";
        feld = document.createElement("input");
        feld.inputMode = "decimal";
        feld.placeholder = "z. B. 1:05,20";
        label.append(feld);
        karte.append(label);
      }
      taste.addEventListener("click", () => bahnMelden(bahn, feld?.value));
      karte.append(taste);
      return karte;
    }));
  }
  aktuell.forEach((bahn, index) => {
    const aktiv = ["FREIGEGEBEN", "START_UNSICHER", "LAEUFT"].includes(lauf.zustand);
    bahnen.children[index].querySelector("button").disabled = !aktiv || !!statusZu(lauf.versuch, "Bahn " + bahn.nummer)
      || (bahn.telefonzeit && !schaetzung);
  });
  byId("meldungen").replaceChildren(...speicher.meldungen.map(eintrag => {
    const li = document.createElement("li");
    const grund = eintrag.grund ? " · " + (fehlerText[eintrag.grund] || fehlerText.abgelehnt) : "";
    li.textContent = eintrag.bezeichnung + " · " + eintrag.status + grund;
    return li;
  }));
}

function dauer(text) {
  const match = /^(?:(\d+):([0-5]\d)|(\d+))(?:[,.](\d{1,6}))?$/.exec(text.trim());
  if (!match) return null;
  const sekunden = match[3] === undefined ? Number(match[1]) * 60 + Number(match[2]) : Number(match[3]);
  const wert = sekunden * 1_000_000 + Number((match[4] || "").padEnd(6, "0"));
  return Number.isSafeInteger(wert) && wert > 0 ? wert : null;
}

function bahnMelden(bahn, eingabe) {
  const zeit = jetzt();
  const versuch = lauf?.versuch;
  const bezeichnung = "Bahn " + bahn.nummer;
  if (!versuch || statusZu(versuch, bezeichnung)) return;
  const ereignis = crypto.randomUUID();
  const daten = { versuch, ereignis, bahn: bahn.nummer };
  if (bahn.telefonzeit) {
    if (!schaetzung) return;
    Object.assign(daten, { zeit, epoche, schaetzung: schaetzung.id });
  } else {
    daten.dauer = dauer(eingabe);
    if (daten.dauer === null) {
      melden("Bitte eine positive Stoppuhrzeit eingeben, z. B. 1:05,20.");
      return;
    }
  }
  if (meldungSetzen({ id: ereignis, bezeichnung, pfad: bahn.telefonzeit ? "ziel" : "stoppuhr",
    daten, status: "Ausstehend" })) {
    melden("");
    void senden();
  }
}

async function neuAbgleichen(versuch) {
  let telefonGesendet = jetzt();
  let telefonEmpfangen;
  const probe = await api("uhr", { versuch, epoche }, () => { telefonEmpfangen = jetzt(); });
  const zeit = performance.getEntriesByName(uhrAdresse, "resource").at(-1);
  if (zeit && Math.round(zeit.startTime * 1000) >= telefonGesendet
      && zeit.requestStart >= zeit.startTime && zeit.responseStart >= zeit.requestStart && zeit.requestStart > 0) {
    telefonGesendet = Math.round(zeit.requestStart * 1000);
    telefonEmpfangen = Math.round(zeit.responseStart * 1000);
  }
  return api("probe", { epoche, probe: probe.probe, telefonGesendet, telefonEmpfangen });
}

function schnelleProben() {
  return lauf?.abgleich && (ersteProben < 4 || (lauf.zustand === "BEREIT" && !lauf.bereit));
}

async function takt() {
  try {
    if (speicher.zugang) {
      const neu = await api("lauf");
      if (neu.versuch !== lauf?.versuch) {
        schaetzung = null;
        ersteProben = 0;
        abgleichFaellig = 0;
      }
      lauf = neu;
      if (lauf.abgleich && performance.now() >= abgleichFaellig && !startAktiv && !document.hidden) {
        try {
          abgleichLaeuft = neuAbgleichen(lauf.versuch);
          schaetzung = await abgleichLaeuft;
          ersteProben++;
          abgleichFaellig = performance.now() + (schnelleProben() ? 0 : schaetzung.auffrischungMs);
        } catch (error) {
          if (error.status === 401) throw error;
          abgleichFaellig = performance.now() + ABGLEICH_INTERVALL_MS;
        } finally {
          abgleichLaeuft = null;
        }
      }
      byId("verbindung").textContent = lauf.versuch
        ? (statusText[lauf.zustand] || "") + (lauf.zustand === "BEREIT" && !lauf.bereit ? " · Uhrabgleich läuft" : "")
        : "Gekoppelt · wartet auf Zuweisung";
      void senden();
    } else {
      byId("verbindung").textContent = "Handy mit dem Wettkampftisch koppeln";
    }
  } catch (error) {
    if (error.status === 401) {
      schreiben({ ...speicher, zugang: null });
      lauf = null;
      schaetzung = null;
      melden(fehlerText.nichtGekoppelt);
    } else {
      byId("verbindung").textContent = "Verbindung unterbrochen · Meldungen bleiben gespeichert";
    }
  } finally {
    darstellen();
    setTimeout(takt, schnelleProben() ? ABGLEICH_INTERVALL_MS : 450);
  }
}

async function audioBereit() {
  audio ??= new AudioContext();
  await audio.resume();
  if (audio.state !== "running") throw new Error("audio");
}

function tonDaten(ms, beginn, kurz = false) {
  const oscillator = audio.createOscillator();
  const lautstaerke = audio.createGain();
  oscillator.type = "sawtooth";
  oscillator.frequency.value = kurz ? 700 : 415;
  lautstaerke.gain.setValueAtTime(0.0001, beginn);
  lautstaerke.gain.exponentialRampToValueAtTime(0.12, beginn + 0.012);
  lautstaerke.gain.setValueAtTime(0.12, beginn + ms / 1000 - 0.05);
  lautstaerke.gain.exponentialRampToValueAtTime(0.0001, beginn + ms / 1000);
  oscillator.connect(lautstaerke).connect(audio.destination);
  oscillator.onended = () => {
    oscillator.disconnect();
    lautstaerke.disconnect();
    if (!kurz) {
      tonAktiv = false;
      darstellen();
    }
  };
  return oscillator;
}

function countdownAbspielen(beginn) {
  for (let sekunde = 0; sekunde < 3; sekunde++) {
    const kurz = beginn + sekunde;
    const oscillator = tonDaten(120, kurz, true);
    oscillator.start(kurz);
    oscillator.stop(kurz + 0.12);
  }
  return beginn + 3;
}

function hoechsteZeit(zeit, uhr) {
  const tisch = zeit + uhr.versatz;
  return tisch + uhr.unsicherheit + Math.ceil(Math.abs(tisch - uhr.bezugszeit) * uhr.driftPpm / 1_000_000);
}

async function start() {
  if (startAktiv || tonAktiv || !lauf?.bereit || !schaetzung || statusZu(lauf.versuch, "Start")) return;
  startAktiv = true;
  darstellen();
  const versuch = lauf.versuch;
  const countdown = lauf.countdown;
  const ereignis = crypto.randomUUID();
  let grunddaten;
  let gestartet = false;
  try {
    await audioBereit();
    const vorlauf = 0.06;
    let audioZeit;
    if (countdown) {
      audioZeit = countdownAbspielen(audio.currentTime + vorlauf);
      await warten((audioZeit - audio.currentTime - 0.45) * 1000);
      if (document.hidden || versuch !== lauf?.versuch) throw new Error("deadline");
    }
    if (abgleichLaeuft) await abgleichLaeuft;
    const uhr = schaetzung;
    if (!uhr || versuch !== lauf?.versuch) throw new Error("deadline");
    const grant = await api("start", { versuch, epoche, schaetzung: uhr.id, anfrage: crypto.randomUUID() });
    grunddaten = { versuch, freigabe: grant.freigabe, ereignis };
    if (countdown) {
      await warten(Math.max(0, (audioZeit - audio.currentTime - vorlauf) * 1000));
    }
    const bisHorn = audioZeit === undefined ? vorlauf : audioZeit - audio.currentTime;
    audioZeit ??= audio.currentTime + vorlauf;
    const zeit = jetzt() + Math.round(bisHorn * 1_000_000);
    const meldung = { id: ereignis, bezeichnung: "Start", pfad: "startmeldung", status: "Ausstehend",
      daten: { ...grunddaten, zeit, epoche, schaetzung: uhr.id, audio: "UNBEKANNT" } };
    if (document.hidden || audio.state !== "running" || bisHorn <= 0 || versuch !== lauf?.versuch
      || hoechsteZeit(zeit + grant.tonReserve, uhr) >= grant.frist) {
      throw new Error("deadline");
    }
    const oscillator = tonDaten(grant.horndauer, audioZeit);
    if (!meldungSetzen(meldung) || audio.currentTime >= audioZeit || document.hidden
      || hoechsteZeit(Math.max(zeit, jetzt()) + grant.tonReserve, uhr) >= grant.frist) {
      oscillator.disconnect();
      throw new Error("deadline");
    }
    oscillator.start(audioZeit);
    gestartet = true;
    tonAktiv = true;
    oscillator.stop(audioZeit + grant.horndauer / 1000);
    if (countdown) await warten(Math.max(0, (audioZeit - audio.currentTime) * 1000));
    if (grant.kalibriert && typeof audio.getOutputTimestamp === "function") {
      await warten(100);
      const ausgabe = audio.getOutputTimestamp();
      if (ausgabe.contextTime >= audioZeit && ausgabe.performanceTime > 0) {
        const beginn = Math.round((ausgabe.performanceTime - (ausgabe.contextTime - audioZeit) * 1000) * 1000);
        meldungSetzen({ ...meldung, daten: { ...meldung.daten, zeit: beginn, audio: "AUSGABE", ausgabe: beginn } });
      }
    }
  } catch (error) {
    if (grunddaten && !gestartet) {
      const daten = { ...grunddaten, audio: "AUSGEFALLEN" };
      if (!meldungSetzen({ id: ereignis, bezeichnung: "Startversuch", pfad: "startmeldung", daten,
        status: "Ausstehend" })) {
        try { await api("startmeldung", daten); } catch { /* Der Tisch behandelt einen fehlenden Bericht als unsicher. */ }
      }
    }
    melden(fehlerText[error.grund] || "Startsignal konnte nicht sicher ausgelöst werden.");
  } finally {
    startAktiv = false;
    darstellen();
    void senden();
  }
}

byId("paarung").addEventListener("submit", async event => {
  event.preventDefault();
  if (!lokalVerfuegbar) {
    melden(fehlerText.speicher);
    return;
  }
  try {
    const wert = await api("paaren", { name: byId("name").value.trim(), code: byId("code").value.trim() });
    if (schreiben({ ...speicher, zugang: wert.zugang })) {
      byId("code").value = "";
      melden("");
    }
  } catch (error) {
    melden(fehlerText[error.grund] || "Wettkampftisch nicht erreichbar.");
  }
});
byId("start").addEventListener("click", start);
byId("testton").addEventListener("click", async () => {
  if (tonAktiv || startAktiv) return;
  tonAktiv = true;
  darstellen();
  try {
    await audioBereit();
    let beginn = audio.currentTime + 0.025;
    const ms = lauf.horndauer;
    if (lauf.countdown) beginn = countdownAbspielen(beginn);
    const oscillator = tonDaten(ms, beginn);
    oscillator.start(beginn);
    oscillator.stop(beginn + ms / 1000);
  } catch {
    tonAktiv = false;
    darstellen();
    melden("Testton konnte nicht abgespielt werden.");
  }
});
window.addEventListener("online", () => { abgleichFaellig = 0; void senden(); });
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) {
    schaetzung = null;
    ersteProben = 0;
    abgleichFaellig = 0;
    darstellen();
  }
});
darstellen();
performance.addEventListener("resourcetimingbufferfull", () => performance.clearResourceTimings());
void takt();
