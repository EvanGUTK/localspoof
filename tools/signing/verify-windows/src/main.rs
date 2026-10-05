use base64::{engine::general_purpose::STANDARD, Engine};
use std::{fs::File, io::Read, path::Path};
fn bounded(path:&Path,maximum:u64)->Result<Vec<u8>,Box<dyn std::error::Error>>{
 let file=File::open(path)?;let size=file.metadata()?.len();
 if size==0 || size>maximum{return Err("Artifact bound exceeded".into());}
 let mut bytes=Vec::new();file.take(maximum+1).read_to_end(&mut bytes)?;
 if bytes.len() as u64>maximum{return Err("Artifact grew beyond bound".into());}
 Ok(bytes)
}
fn main()->Result<(),Box<dyn std::error::Error>>{
 let args:Vec<_>=std::env::args().collect();if args.len()!=4{return Err("Expected installer, signature, pinned public key".into());}
 let decode=|bytes:Vec<u8>|->Result<String,Box<dyn std::error::Error>>{Ok(String::from_utf8(STANDARD.decode(String::from_utf8(bytes)?.trim())?)?)};
 let public=minisign_verify::PublicKey::decode(&decode(bounded(Path::new(&args[3]),8192)?)?)?;
 let signature=minisign_verify::Signature::decode(&decode(bounded(Path::new(&args[2]),8192)?)?)?;
 public.verify(&bounded(Path::new(&args[1]),256*1024*1024)?,&signature,false)?;
 println!("Windows installer verified against pinned updater identity.");Ok(())
}
