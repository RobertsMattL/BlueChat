# CodeFlow Android Project

## Build Validation Policy

**IMPORTANT: Under no circumstances should you validate your changes using Gradle building.**

Do not run `./gradlew build`, `./gradlew assembleDebug`, or any other Gradle build commands to verify your code changes. The CI/CD pipeline will handle build validation - if there are build failures, they will be reported through GitHub Actions notifications.

If a build failure is reported:
1. Query the GitHub API to retrieve the workflow run details and logs
2. Analyze the failure output to identify the root cause
3. Fix the identified issues in the code

Trust that your code changes are syntactically correct without running local builds.

## Logging Policy

**All logging MUST use `CodeFlowLogger` (`com.test.app.CodeFlowLogger`).** Do not use `android.util.Log`, `Timber`, `println`, or any other logging mechanism.

Available methods:
- `CodeFlowLogger.debug(tag, message, metadata?)` — routine diagnostic info
- `CodeFlowLogger.info(tag, message, metadata?)` — noteworthy events
- `CodeFlowLogger.warning(tag, message, metadata?)` — potential issues
- `CodeFlowLogger.error(tag, message, throwable?, metadata?)` — errors (triggers immediate flush)
- `CodeFlowLogger.fatal(tag, message, throwable?, metadata?)` — critical failures (triggers synchronous flush)

Logs are batched locally and uploaded in batches of 1000 to the CodeFlow logging API (`POST /api/logs/{application_id}`). Uploads are triggered automatically at lifecycle transitions (pause, stop, destroy, start), on memory pressure, and when the buffer reaches 1000 entries. Errors and fatals trigger immediate upload.

**When writing any new code, always add robust logging:**
- Log entry/exit of significant operations (network calls, database operations, navigation)
- Log all caught exceptions with `CodeFlowLogger.error()` or `CodeFlowLogger.fatal()`
- Log state transitions and important decision points
- Use meaningful tags (typically the class name) and descriptive messages
- Choose the appropriate log level — don't log routine operations as errors
