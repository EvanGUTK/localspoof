# Desktop local import keepalive

Paired controller polling now sends its existing heartbeat while the desktop awaits local GeoPack text reads, SHA-256 hashing, or PMTiles index opening. This closes the gap where local import work held the UI busy flag and suspended the short controller lease.

Only those independent local awaits enable the keepalive. Before their scope exits, the flag clears and any already-sent heartbeat finishes, so a subsequent phone import captures the latest acknowledged revision. Phone operations such as CommitPlan, ImportGeoPack, BeginGeoPack, chunk transfer, and CommitGeoPack keep their existing serialization; a busy phone request does not enable extra heartbeat traffic.

Local work uses controller epochs and ownership identity. Stop or connection loss cannot let an old completion clear another operation's keepalive flag. GeoPack results additionally verify the preview epoch, so Clear cannot restore a pack or submit it to the phone after discarding activity. PMTiles opening retains the map adapter's archive cancellation guards.

Verification: 59 desktop UI/model/geography/privacy tests passed. Eight new UI regressions cover delayed local file completion, Stop, Clear, heartbeat failure, an outstanding heartbeat drained before an import captures revision, delayed hashing, and PMTiles completion/cancellation. An explicit check confirms no heartbeat queues during the phone import itself. Source privacy and protocol fixture checks also passed.

A long serialized native/phone request can still outlast the lease; this narrow change does not alter lease timing or native request scheduling. Physical-device timing remains qualification work.
