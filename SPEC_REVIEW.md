# CalOnyx — Spec review (PDF + overview), 2026-10-06 20:15 AKDT

## Sources reviewed
- `~/workspace/user/files/calonyx_project_overview.txt` (uploaded ~04:06 UTC)
- `~/workspace/user/files/calonyx_blueprints.pdf` — 7 pages, LibreOffice Impress visual mockups (uploaded ~04:06 UTC)

Both say the same thing at the top level. The PDF adds UI-level details the text spec doesn't have.
MEMORY.md already captures most of the spec; the delta below is what only the PDF establishes.

## PDF-only details (delta vs text spec / memory)

### Day cascade (Calendar/Event list view)
- Dotted hour lines; timeline scrollable **and zoomable** along the y-axis.
- Tapping the timeline or an empty space opens the Event Editor; the hour where the tap occurred auto-fills start/end.
- **Tap-and-hold** an event to move it.
- Simultaneous events auto-resize side-by-side.
- Each event tile shows title + one or two associated names + location if room; a small dot indicates status.
- Status color key (4 states): unmarked (no color), green = happened + recorded, yellow = happened unrecorded, red = didn't happen.

### Event types
- Default types: Contact, Call, Text, Date, Appointment, Note, Work, Meal. Users can add types and recolor them.
- Type color is separate from the UI theme palette.

### Event Editor / details
- Save / discard buttons.
- Title field; editable start/end times.
- Type: dropdown list.
- Status: tappable button cycling between three choices — 'happened', 'didn't happen', 'not recorded'. (Note: 3-way editor UI vs 4 data states — 'not recorded' likely covers both UNMARKED and HAPPENED_UNRECORDED; needs a mapping decision.)
- Description/report field.
- List of people: tapping a name opens that contact's timeline; "+ add person" control.
- Location field. Whole view scrollable along y-axis.

### Home view
- "Currently:" section listing currently-occurring event titles (or "nothing" if none); each title links to event details.
- "Next Event in X h : XX m" — t-minus countdown.
- Month preview grid: each day box shows the date and a **fill % based on a 16-hour day**, and each box links to that day.
- Fill % can exceed 100% (blueprint shows 120% for one day) — open question: display raw or cap at 100%.

### Contacts view
- Search bar at top; "Sort by A-Z" control.
- Scrollable list fed by the device's contacts (read/write sync with runtime permission; app must work without it).
- Tapping a contact opens the contact timeline.

### Contact timeline
- General info from the phone (phone number, address), relationship tag (default set: family, friend, co-worker, peer, stranger; user-definable).
- Event list ordered most → least recent; tapping an event opens its details; scrollable.

### Import/export
- Events: iCal (vCalendar 2.0); contacts: vCard 4.0; custom data via `X-` prefix. Out of scope: backend sync, notifications.

## Current build state (checked 2026-10-06 20:15 AKDT)
- `~/workspace/calonyx/` scaffolds a Jetpack Compose app (minSdk 26, Room 2.6.1, KSP, Compose BOM 2024.06.00).
- Data layer complete and consistent with the spec: Event, Contact, EventType (colorArgb), RelationshipTag, EventContactCrossRef (many-to-many), ThemeSettings, DAOs, repository, 4-state EventStatus enum.
- `MainActivity` is a placeholder screen; the three screens + event editor + contact timeline are **not yet built**.

## Environment limitation — important
- This runtime has **no JDK and no Android SDK installed** (`java` not found; ANDROID_HOME/ANDROID_SDK_ROOT unset; no SDK dirs). Gradle build / APK assembly cannot run here right now.
- Earlier the user was told an APK could be built. That is not currently true from this environment. Options: (a) install a JDK + Android SDK here (~1–2 GB download, needs network + disk); (b) user opens the project in Android Studio and builds there; (c) set up CI. Decide before promising an APK.
- Code can still be written and reviewed here; it just can't be compiled until an SDK exists.

## Open decisions for the user
1. Status mapping: editor shows 3 states; data model has 4. Map 'not recorded' → which of UNMARKED / HAPPENED_UNRECORDED?
2. Month fill %: show values >100% raw (as in blueprint) or cap at 100%?
3. Device contacts write-back: when adding a contact in-app, create it in the device address book too, or keep app-local? (Must work without permission either way.)
4. Build path: install SDK here, or user builds in Android Studio?
5. iCal/vCard round-trip scope: full fidelity for custom fields, or minimal first pass?

## Proposed milestone 2 (once decisions 1–2 are settled)
Compose screens per blueprint: Home (currently/next/countdown/month %), Calendar (zoomable day cascade, tap-hold move, tap-to-create), Contacts (search/sort list), Event editor dialog/screen, Contact timeline. Then milestone 3: import/export + theming UI.
