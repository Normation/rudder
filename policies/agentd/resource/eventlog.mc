; // EventLogMsgs.mc
; // ********************************************************

; // Use the following commands to build this file:

; //   mc -s EventLogMsgs.mc
; //   rc EventLogMsgs.rc
; //   link /DLL /SUBSYSTEM:WINDOWS /NOENTRY /MACHINE:x86 EventLogMsgs.Res
; // ********************************************************

; // Linux version
; //    x86_64-w64-mingw32-windmc eventlog.mc
; //    x86_64-w64-mingw32-windres -i eventlog.rc -o eventlog.o
; //    x86_64-w64-mingw32-ld --dll --subsystem windows -e 0 -o eventlog.dll -T eventlog.ld -s eventlog.o
; // ********************************************************

; // Use with
; //   New-EventLog -Source AgentD -LogName Rudder -CategoryResourceFile c:\vagrant\evenlog.dll -MessageResourceFile c:\vagrant\evenlog.dll -ParameterResourceFile c:\vagrant\evenlog.dll

; // - Event categories -
; // Categories must be numbered consecutively starting at 1.
; // ********************************************************

MessageId=0x1
Severity=Success
SymbolicName=INSTALL_CATEGORY
Language=English
Installation
.

MessageId=0x2
Severity=Success
SymbolicName=QUERY_CATEGORY
Language=English
Database Query
.

MessageId=0x3
Severity=Success
SymbolicName=REFRESH_CATEGORY
Language=English
Data Refresh
.

; // - Event messages -
; // *********************************
; // IDs come from calls to ReportEventW
; // Add 0x20000000 with an OR operation to find the real ID
; // see https://stackoverflow.com/questions/47181193/write-in-windows-eventlog-with-delphi-event-id-not-found
; // see https://learn.microsoft.com/en-us/windows/win32/eventlog/event-identifiers

; // Next event IDs are for winlog2 crate for rust
; // Facility is null there
; // Severity can be: Informational, Warning, Error, Critical (not used in winlog2)

MessageId = 0x60000005
Severity = Informational
SymbolicName = GENERIC_TRACE
Language=English
TRACE: %1
.


MessageId = 0x60000004
Severity = Informational
SymbolicName = GENERIC_DEBUG
Language=English
DEBUG: %1
.

MessageId = 0x60000003
Severity = Informational
SymbolicName = GENERIC_INFO
Language=English
INFO: %1
.

MessageId = 0xA0000002
Severity = Warning
SymbolicName = GENERIC_WARNING
Language=English
WARN: %1
.

MessageId = 0xE0000001
Severity = Error
SymbolicName = GENERIC_ERROR
Language=English
ERROR: %1
.

