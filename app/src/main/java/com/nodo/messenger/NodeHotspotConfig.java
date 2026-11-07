package com.nodo.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private on-device hotspot preference. The passphrase is encrypted in Android Keystore. */
final class NodeHotspotConfig {
    private static final String PREF="nodo_hotspot_preferences_v1";
    private static final String ALIAS="nodo_beta_hotspot_aes_gcm_v1";
    private NodeHotspotConfig(){}
    static String savedName(Context ctx){return prefs(ctx).getString("ssid","NODO-RED");}
    static String savedPassword(Context ctx){
        String value=prefs(ctx).getString("encrypted_password","");
        if(value.isEmpty())return "";
        try{
            String[] pieces=value.split(":",2);
            if(pieces.length!=2)return "";
            byte[] iv=Base64.decode(pieces[0],Base64.NO_WRAP);
            byte[] ciphertext=Base64.decode(pieces[1],Base64.NO_WRAP);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,fetchKey(),new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
        }catch(Exception ignored){return "";}
    }
    static String validationError(String name,String password){
        if(name==null || name.trim().isEmpty())return "Escribe un nombre para la red";
        if(name.getBytes(StandardCharsets.UTF_8).length>32)return "El nombre no puede superar 32 bytes";
        if(password==null || password.length()<8 || password.length()>63)
            return "La contraseña debe tener entre 8 y 63 caracteres";
        for(int i=0;i<password.length();i++)if(password.charAt(i)<32 || password.charAt(i)>126)
            return "Utiliza letras, números o símbolos ASCII en la contraseña";
        return null;
    }
    static boolean save(Context ctx,String name,String password){
        if(validationError(name,password)!=null)return false;
        try{
            byte[] bytes=password.getBytes(StandardCharsets.UTF_8);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,fetchKey());
            String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+
                Base64.encodeToString(cipher.doFinal(bytes),Base64.NO_WRAP);
            return prefs(ctx).edit().putString("ssid",name).putString("encrypted_password",encrypted).commit();
        }catch(Exception ignored){return false;}
    }
    private static SharedPreferences prefs(Context ctx){
        return ctx.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    private static SecretKey fetchKey()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
}
