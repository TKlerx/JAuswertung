# ARES export contract

- ASCII with semicolon delimiters and CRLF. Emit one header and one result row for each occupied physical lane, ordered by lane number.
- Use the entered numeric event, ARES round and lap zero, and heat number minus one. Leave the result text fields and backup times unused.
- Write the approved time in whole milliseconds at hundredth-second precision. Reject values that round outside the supported positive range. Export each heat to a new file.
- The consumer is stock JAuswertung 18.1.1. The operator compares event, heat and the occupied lanes before applying. The importer has no knowledge of the phone assignment or uncertainty.

The phone-side contract is unchanged. See the [Android consumer contract](../../002-native-race-timing/contracts/desk-api.md).
