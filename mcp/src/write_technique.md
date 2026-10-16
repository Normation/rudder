Write a Rudder technique for this goal:

{goal}

Follow these steps, using the Rudder MCP tools:

1. Clarify the goal with me if it is ambiguous: what must be true on the nodes, and on which
   systems (Linux, Windows or both). Ask before drafting rather than guessing.
2. Pick the methods: list them with `methods`, then get the full metadata of each method you
   plan to use, for its exact parameters, constraints and supported agents. Do not use
   deprecated methods. Check that every method supports the target systems.
3. If a module is involved (template, augeas, commands, system updates, secedit), read its
   documentation with `documentation` before using it.
4. Draft the technique in the YAML format described below. Use parameters for values that
   differ between uses of the technique, instead of hard-coding them.
5. Compile it with `compile_technique`. Fix every reported error and compile again, until it
   compiles cleanly. Never show me a technique that does not compile.
6. For each template in the technique, render it with `render_template` and realistic sample
   data, and fix it until the output is what the file should contain.
7. Show me the final technique, then explain briefly what it does on each target system and
   which parameters I need to set. Do not import it into Rudder: I will do it.

Technique format reference:

{syntax}
