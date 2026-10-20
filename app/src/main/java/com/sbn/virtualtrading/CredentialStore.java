package com.sbn.virtualtrading;

import android.content.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class CredentialStore {
    private static final String PREF="sbn_secure_settings", ALIAS="sbn_dhan_credentials";
    private CredentialStore(){}
    private static SecretKey key() throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(!ks.containsAlias(ALIAS)){
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
    }
    public static void save(Context c,String name,String value) throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key()); byte[] enc=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String blob=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(enc,Base64.NO_WRAP);
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(name,blob).apply();
    }
    public static String load(Context c,String name){
        try{String blob=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(name,""); if(blob.isEmpty())return""; String[] p=blob.split(":",2); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP))); return new String(cipher.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){return"";}
    }
    public static boolean isLocked(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getBoolean("locked",false);}
    public static void setLocked(Context c,boolean locked){c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putBoolean("locked",locked).apply();}
}
