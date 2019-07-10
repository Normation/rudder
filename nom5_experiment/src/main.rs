mod parser;

fn main() {
    //let h = pinput("@format=x\nyoupi", "file");
    //let (i, o) = pheader(h).unwrap();
    //println!("Header version={:?}, rest={:?}", o, i);
    println!("Header");

    println!("x={}", (|x: i32| { x*2 }) (2));
}
