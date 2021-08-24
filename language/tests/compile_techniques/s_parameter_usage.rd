@format=0
resource R1 (a,b)
R1 state enabled(c) {
}

resource R2(a)
R2 state config(b) {
  R1(a,"x").enabled(b)
}
