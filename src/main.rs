mod parser;

fn main() {
    //let h = pinput("@format=x\nyoupi", "file");
    //let (i, o) = pheader(h).unwrap();
    //println!("Header version={:?}, rest={:?}", o, i);
    println!("Header");

    println!("x={}", { let g=|x| { x*2 }; g } (2));
}
