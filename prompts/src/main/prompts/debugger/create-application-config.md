Create Application Run Configuration

Create a run configuration programmatically: enumerate the registered configuration types and factories (platform API), then create one from a chosen factory. Includes an IDEA Application example.

## Enumerate configuration types and create from a factory (every IDE)

Run configuration types are registered on the platform `com.intellij.configurationType`
extension point. Enumerate them to discover what kinds of configurations the current IDE
supports (each IDE ships its own set — Application/JUnit in IDEA, Python in PyCharm,
Go in GoLand, npm in WebStorm, ...), then create and register a configuration from a
chosen factory:

```kotlin
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType

// 1. Discover the configuration types this IDE offers
val types = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
for (type in types) {
    val factories = type.configurationFactories.joinToString { it.name }
    println("${type.id} (${type.displayName}) factories: $factories")
}

// 2. Create a configuration from a chosen factory
val typeId = "TODO"       // TODO: pick a type id printed above
val configName = "MyApp"  // TODO: configuration name

val chosenType = types.firstOrNull { it.id == typeId }
    ?: error("Configuration type not found: '$typeId'. Candidates: ${types.map { it.id }}")
val factory = chosenType.configurationFactories.firstOrNull()
    ?: error("Type '$typeId' has no configuration factories")

val runManager = RunManager.getInstance(project)
if (runManager.findConfigurationByName(configName) != null) {
    println("Run configuration already exists: $configName")
    return
}

val settings = runManager.createConfiguration(configName, factory)
// Type-specific setup goes here: cast settings.configuration to the factory's
// configuration class and set its properties (main class, script path,
// working directory, environment, ...).
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings
println("Created run configuration: $configName (${chosenType.displayName})")
```

###_IF_IDE[IU]_###
## IDEA: Application configuration for a Kotlin/Java main class

`ApplicationConfiguration` is the JVM main-class configuration from the Java plugin:

```kotlin[IU]
import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.openapi.module.ModuleManager

val mainClassName = "com.example.MainKt"  // TODO: Set your main class FQN
val configName = "MyApp"  // TODO: Set configuration name

val runManager = RunManager.getInstance(project)

// Check if configuration already exists
val existing = runManager.findConfigurationByName(configName)
if (existing != null) {
    println("Run configuration already exists:", configName)
    return
}

val factory = ApplicationConfigurationType.getInstance().configurationFactories.first()
val settings = runManager.createConfiguration(configName, factory)
val config = settings.configuration as ApplicationConfiguration

config.mainClassName = mainClassName

// Set module (pick the first available or filter by name)
val modules = ModuleManager.getInstance(project).modules
val module = modules.firstOrNull { it.name.endsWith(".main") }
    ?: modules.firstOrNull()
if (module != null) {
    config.setModule(module)
}

settings.storeInDotIdeaFolder()
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings

println("Created run configuration:", configName, "main:", mainClassName)
```
###_ELSE_IF_IDE[RD]_###
## Rider note

`ApplicationConfiguration` is JVM-specific and does not exist in Rider. To run or debug
.NET tests, use Rider's native context actions (`RiderUnitTestRunContextAction` /
`RiderUnitTestDebugContextAction`) — see [Run Test at Caret](mcp-steroid://test/run-test-at-caret).
###_END_IF_###

# See also

Related debugger operations:
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start existing config in debug mode
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Set breakpoint before debugging

Related test operations:
- [Run Test at Caret](mcp-steroid://test/run-test-at-caret) - Run/debug a test via context action (every IDE)

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
