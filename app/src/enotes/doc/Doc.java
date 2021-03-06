/*
 * (c) 2009.-2014. Ivan Voras <ivoras@fer.hr>
 * Released under the 2-clause BSDL.
 */

package enotes.doc;

import java.io.*;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Document abstraction; contains load and save routines.
 *
 * @author ivoras
 */
public class Doc {

    /** Crypto mode to use while writing the file */
    public static final String CRYPTO_MODE = "AES/CBC/PKCS5Padding";
    
    /** The short name of the crypt algorithm used on the files */
    public static final String CRYPTO_ALG = "AES";
    

    private String text;
    private DocMetadata docm;


    public Doc(String text, DocMetadata docm) {
        this.text = text;
        this.docm = docm;
    }


    public Doc() {
        text = "";
        docm = new DocMetadata();
    }

    public boolean doSave(File f) throws FileNotFoundException, IOException, DocPasswordException {
        FileOutputStream fout = new FileOutputStream(f);
        boolean b = doSaveFOS(fout);
        try {
            fout.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return b;
    }

    /**
     * Saves the currently edited document to the given file.
     *
     * @param fout
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     * @throws DocPasswordException 
     */
    public boolean doSaveFOS(OutputStream fout) throws FileNotFoundException, IOException, DocPasswordException  {
        assert(docm.key != null);

        String current_user = System.getProperty("user.name");
        if (docm.saveHistory.size() != 0) {
            SaveMetadata smd = docm.saveHistory.get(docm.saveHistory.size()-1);
            if (!smd.username.equalsIgnoreCase(current_user)) {
                smd = new SaveMetadata(System.currentTimeMillis(), current_user);
                docm.saveHistory.add(smd);
            } else
                smd.timestamp = System.currentTimeMillis();
        } else
            docm.saveHistory.add(new SaveMetadata(System.currentTimeMillis(), current_user));

        if (docm.key == null)
        	throw new DocPasswordException("Key not set in DocMetadata");
        
        //FileOutputStream fout = new FileOutputStream(f);
        BufferedOutputStream bout = new BufferedOutputStream(fout);

        bout.write(DocMetadata.SIGNATURE);
        bout.write(DocMetadata.VERSION_FORMAT);
        bout.write(DocMetadata.VERSION_MINOR);

        byte[] iv = new byte[16];
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.nextBytes(iv);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }

        byte[] keyHash = Util.sha1hash(Util.concat(docm.key, iv));

        bout.write(keyHash, 0, 2); /* Save password hash */
        bout.write(iv);

        AlgorithmParameterSpec paramSpec = new IvParameterSpec(iv);
        Cipher ecipher = null;
        try {
            ecipher = Cipher.getInstance(CRYPTO_MODE);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (NoSuchPaddingException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
        try {
            ecipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(docm.key, 0, 16, CRYPTO_ALG), paramSpec);
        } catch (InvalidKeyException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (InvalidAlgorithmParameterException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }

        CipherOutputStream cout = new CipherOutputStream(bout, ecipher);
        GZIPOutputStream zout = new GZIPOutputStream(cout);
        DataOutputStream dout = new DataOutputStream(zout);

        docm.saveMetadata(dout);
        byte[] ddata = text.getBytes("UTF-8");
        dout.writeInt(ddata.length);
        dout.write(ddata);
        System.out.println("Written "+ ddata.length + " bytes");
        //dout.writeUTF(text); // Java doesn't work with strings > 64 KiB :DD

        dout.close();
        try {
	        zout.close();
	        cout.close();
	        bout.close();
	        //fout.close();
        } catch (IOException e) {
        	e.printStackTrace();
        }
        return true;
    }


    /**
     * Opens the specified file to be the currently edited document.
     *
     * @param fOpen
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    public boolean doOpen(File fOpen, String pwd) throws FileNotFoundException, IOException, DocException, DocPasswordException {
        FileInputStream fin = new FileInputStream(fOpen);
        boolean b = doOpenFIS(fin, pwd, fOpen);
        fin.close();
        return b;
    }

    /**
     * Opens the specified FileInputStream to be the currently edited document.
     *
     * @param fin
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    public boolean doOpenFIS(InputStream fin, String pwd, File fOpen) throws FileNotFoundException, IOException, DocException, DocPasswordException {
        //FileInputStream fin = new FileInputStream(fOpen);
        BufferedInputStream bin = new BufferedInputStream(fin);

        byte[] sig = new byte[DocMetadata.SIGNATURE.length];
        bin.read(sig);
        boolean equal = true;
        for (int i = 0; i < sig.length; i++)
            if (sig[i] != DocMetadata.SIGNATURE[i])
                equal = false;
        if (!equal)
            throw new DocException("File is not a valid Encrypted Notepad file: "+fOpen.getAbsolutePath());
        byte ver_format = (byte) bin.read();
        if (ver_format > DocMetadata.VERSION_FORMAT)
            throw new DocException("File is a Encrypted Notepad file but cannot be opened by this version of the program: "+fOpen.getAbsolutePath());
        byte ver_minor = (byte) bin.read();
        if (ver_minor > DocMetadata.VERSION_MINOR)
            throw new DocException("File format version is newer than this app version supports");
        byte[] pwdhash = new byte[2];
        bin.read(pwdhash);
        byte[] iv = new byte[16];
        bin.read(iv);

        DocMetadata newdocm = new DocMetadata();
        newdocm.key = Util.sha1hash(pwd);

        if (ver_minor == 0) {
            equal = true;
            for (int i = 0; i < pwdhash.length; i++)
                if (pwdhash[i] != newdocm.key[newdocm.key.length-3+i]) {
                    equal = false;
                    break;
                }
        } else if (ver_minor == 1 || ver_minor == 2) {
            byte[] keyHash = Util.sha1hash(Util.concat(newdocm.key, iv));
            equal = keyHash[0] == pwdhash[0] && keyHash[1] == pwdhash[1];
        } else
            throw new DocException("Cannot read document with ver_minor="+ver_minor);

        if (!equal)
            throw new DocPasswordException("Invalid password!");

        AlgorithmParameterSpec paramSpec = new IvParameterSpec(iv);
        Cipher dcipher = null;
        try {
            dcipher = Cipher.getInstance(CRYPTO_MODE);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (NoSuchPaddingException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
        try {
            dcipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(newdocm.key, 0, 16, CRYPTO_ALG), paramSpec);
        } catch (InvalidKeyException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (InvalidAlgorithmParameterException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }

        CipherInputStream cin = new CipherInputStream(bin, dcipher);
        GZIPInputStream zin = new GZIPInputStream(cin);
        DataInputStream din = new DataInputStream(zin);

        String newtext;
        newdocm.loadMetadata(din);
        if (ver_minor < 2)
            newtext = din.readUTF();
        else {
            int len = din.readInt();
            byte[] ddata = new byte[len];
            int total_read = 0;
            while (total_read < len) {
                int nread = din.read(ddata, total_read, len-total_read);
                total_read += nread;
            }
            System.out.println("Read "+ total_read + " bytes");
            newtext = new String(ddata, "UTF-8");
        }

        din.close();
        zin.close();
        cin.close();
        bin.close();
        // fin.close();
        newdocm.filename = fOpen.getAbsolutePath();

        docm = newdocm;
        text = newtext;
        
        return true;
    }


    public String getText() {
        return text;
    }


    public DocMetadata getDocMetadata() {
        return docm;
    }
}
