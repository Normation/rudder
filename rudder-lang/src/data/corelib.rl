@format=0

global enum os {
  ubuntu,
  debian
}

enum os ~> family {
  ubuntu -> debian,
  debian -> debian
}

# Must be defined, needed by the compiler
enum boolean {
  true,
  false
}

# Must be defined, needed by the compiler
enum outcome {
  kept,
  repaired,
  error
}

