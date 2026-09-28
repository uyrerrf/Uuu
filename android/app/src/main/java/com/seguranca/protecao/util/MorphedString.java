package com.seguranca.protecao.util;

import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import java.util.Arrays;

/**
 * Wrapper para strings que nunca existem em plaintext na memória por mais de um frame.
 * A string é mantida encriptada usando o PolymorphicEngine e só é decodificada
 * momentaneamente quando acessada via get().
 */
@Obfuscate
public class MorphedString implements Comparable<MorphedString> {

    private static final String TAG = "MS";
    private byte[] encryptedData;

    /**
     * Cria MorphedString a partir de string plaintext.
     * A string é imediatamente encriptada.
     */
    public MorphedString(String plaintext) {
        if (plaintext == null) {
            this.encryptedData = null;
        } else {
            try {
                PolymorphicEngine engine = PolymorphicEngine.getInstance();
                byte[] plain = plaintext.getBytes("UTF-8");
                this.encryptedData = engine.morphPayload(plain);
                // Limpa array intermediário
                Arrays.fill(plain, (byte) 0);
            } catch (Exception e) {
                Log.e(TAG, "Encrypt erro: " + e.getMessage());
                try {
                    this.encryptedData = plaintext.getBytes("UTF-8");
                } catch (Exception ex) {
                    this.encryptedData = plaintext.getBytes();
                }
            }
        }
    }

    private MorphedString(byte[] encrypted) {
        this.encryptedData = encrypted;
    }

    /**
     * Decodifica a string, retorna, e re-encripta com nova chave/estratégia.
     * A string plaintext existe na memória apenas durante esta chamada.
     */
    public String get() {
        if (encryptedData == null) return null;

        try {
            PolymorphicEngine engine = PolymorphicEngine.getInstance();
            byte[] plain = engine.demorphPayload(encryptedData);
            String result = new String(plain, "UTF-8");

            // Re-encripta com chave/estratégia possivelmente nova
            byte[] newEncrypted = engine.morphPayload(plain);

            // Limpa dados antigos
            Arrays.fill(encryptedData, (byte) 0);
            Arrays.fill(plain, (byte) 0);

            // Atualiza
            encryptedData = newEncrypted;

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Decrypt erro: " + e.getMessage());
            try {
                return new String(encryptedData, "UTF-8");
            } catch (Exception ex) {
                return new String(encryptedData);
            }
        }
    }

    /**
     * Factory method padrão.
     */
    public static MorphedString of(String s) {
        return new MorphedString(s);
    }

    /**
     * Factory method que retorna null se input for null.
     */
    public static MorphedString ofNullable(String s) {
        if (s == null) return null;
        return new MorphedString(s);
    }

    /**
     * Nunca revela conteúdo via toString.
     */
    @Override
    public String toString() {
        return "[MORPHED]";
    }

    /**
     * Compara pelo valor decodificado.
     */
    @Override
    public int compareTo(MorphedString other) {
        if (other == null) return 1;
        String thisVal = this.get();
        String otherVal = other.get();
        if (thisVal == null && otherVal == null) return 0;
        if (thisVal == null) return -1;
        if (otherVal == null) return 1;
        return thisVal.compareTo(otherVal);
    }

    /**
     * Compara pelo valor decodificado.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MorphedString)) return false;

        MorphedString other = (MorphedString) obj;
        String thisVal = this.get();
        String otherVal = other.get();

        if (thisVal == null && otherVal == null) return true;
        if (thisVal == null || otherVal == null) return false;
        return thisVal.equals(otherVal);
    }

    /**
     * Hash baseado no valor decodificado.
     */
    @Override
    public int hashCode() {
        String val = this.get();
        return val != null ? val.hashCode() : 0;
    }

    /**
     * Limpa dados encriptados da memória.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (encryptedData != null) {
                Arrays.fill(encryptedData, (byte) 0);
                encryptedData = null;
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Verifica se a string decodificada é igual a uma string plain.
     * Útil para comparações rápidas sem criar outro MorphedString.
     */
    public boolean equalsPlain(String plain) {
        if (plain == null) return encryptedData == null;
        String val = this.get();
        return plain.equals(val);
    }

    /**
     * Verifica se o dado interno é null ou vazio.
     */
    public boolean isEmpty() {
        if (encryptedData == null) return true;
        String val = this.get();
        return val == null || val.isEmpty();
    }
}
