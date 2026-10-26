// Compile-checks Hubitat app/driver sources:
//   groovy .github/scripts/groovy-parse-check.groovy <file>...
//
// Each file is compiled as a plain Groovy script up to class generation (no
// class files written). Hubitat's DSL (definition, metadata, preferences,
// mappings, input, ...) is just method calls on the script, which resolve at
// run time, so no stubs are needed. A file that types a variable with a
// hub-only class (hubitat.*, com.hubitat.*) would fail here; use `def` or add
// a stub class to this script if that is ever needed.
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases

if (!args) { println 'FAIL no files given'; System.exit(1) }
int failures = 0
args.each { path ->
    def unit = new CompilationUnit()
    unit.addSource(new File(path))
    try {
        unit.compile(Phases.CLASS_GENERATION)
        println "OK   ${path}"
    } catch (CompilationFailedException e) {
        failures++
        println "FAIL ${path}\n${e.message}"
    }
}
System.exit(failures ? 1 : 0)
