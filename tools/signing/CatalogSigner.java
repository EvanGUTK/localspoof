import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
/** Standard JCA detached catalog signature; release keys/password never enter arguments/output. */
public final class CatalogSigner {
 public static void main(String[] args) throws Exception {
  if(args.length != 4) throw new IllegalArgumentException("Expected key, catalog, signature, public certificate paths.");
  String password=System.getenv("LOCALSPOOF_KEYSTORE_PASSWORD");
  if(password==null) throw new IllegalStateException("Release credentials missing.");
  char[] chars=password.toCharArray();
  try {
   KeyStore store=KeyStore.getInstance("JKS");
   try(var input=Files.newInputStream(Path.of(args[0]))){store.load(input,chars);}
   Certificate certificate=store.getCertificate("localspoof");
   if(!MessageDigest.isEqual(certificate.getEncoded(),Files.readAllBytes(Path.of(args[3])))) throw new SecurityException("Original release identity mismatch.");
   long size=Files.size(Path.of(args[1]));
   if(size<1 || size>65536) throw new IllegalArgumentException("Catalog bound exceeded.");
   byte[] bytes;
   try(var input=Files.newInputStream(Path.of(args[1]))){bytes=input.readNBytes(65537);}
   if(bytes.length==0 || bytes.length>65536) throw new IllegalArgumentException("Catalog bound exceeded.");
   Signature signer=Signature.getInstance("SHA256withRSA");
   signer.initSign((PrivateKey)store.getKey("localspoof",chars)); signer.update(bytes);
   byte[] signature=signer.sign();
   signer.initVerify(certificate); signer.update(bytes);
   if(!signer.verify(signature)) throw new SecurityException("Signature self verification failed.");
   Files.writeString(Path.of(args[2]),Base64.getEncoder().encodeToString(signature));
   System.out.println("Catalog signed and verified with original Android release identity.");
  }finally{Arrays.fill(chars,'\0');}
 }
}
