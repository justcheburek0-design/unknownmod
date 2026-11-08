// Uses Horizon's real archive reader; does not execute mod code or install it.
fn main() {
    for path in std::env::args().skip(1) {
        match horizon_mods::index::native::Bundle::read(std::path::Path::new(&path)) {
            Ok(bundle) => println!("PASS archive {} {}: {} validated source files",bundle.manifest.id,bundle.manifest.version,bundle.files.len()),
            Err(error) => {eprintln!("FAIL {path}: {error}");std::process::exit(1);}
        }
    }
}
