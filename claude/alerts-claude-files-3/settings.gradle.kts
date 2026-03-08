rootProject.name = "event-platform"

include(
    "platform-common",
    "ingestion-service",
    "processing-service",    // includes dispatch + retry (3-service model)
    "audit-service"
)

// Map module directories
project(":platform-common").projectDir      = file("common")
project(":ingestion-service").projectDir    = file("services/ingestion-service")
project(":processing-service").projectDir   = file("services/processing-service")
project(":audit-service").projectDir        = file("services/audit-service")
