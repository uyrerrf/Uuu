package com.seguranca.protecao.util;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import org.lsposed.lsparanoid.Obfuscate;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Engine de ofuscação polimórfica dinâmica.
 * Transforma representações de dados em runtime usando múltiplas estratégias
 * que rotacionam periodicamente, dificultando análise estática e pattern matching.
 *
 * Protocolo de payload polimórfico:
 * [0x02][strategyId 1 byte][keyHint 4 bytes][dados transformados]
 */
@Obfuscate
public class PolymorphicEngine {

    private static final String TAG = "PE";
    private static volatile PolymorphicEngine sInstance;

    private Context appContext;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile byte[] currentKey;
    private volatile int currentStrategyIndex = 0;
    private volatile boolean isRunning = false;
    private Thread reKeyThread;
    private String androidId;

    // Estratégias disponíveis
    private final TransformStrategy[] strategies = {
        new XorChainTransform(),
        new ArithmeticSubstitution(),
        new ByteShuffleTransform(),
        new SplitMergeTransform(),
        new NoiseInjectionTransform(),
        new MultiLayerTransform()
    };

    // Header markers
    public static final byte MORPH_HEADER = 0x02;
    public static final byte PLAIN_HEADER_FRAME = 0x01;

    // ================= INTERFACE =================

    public interface TransformStrategy {
        byte[] encode(byte[] data, byte[] key);
        byte[] decode(byte[] encoded, byte[] key);
        byte getStrategyId();
    }

    // ================= SINGLETON =================

    private PolymorphicEngine() {}

    public static PolymorphicEngine getInstance() {
        if (sInstance == null) {
            synchronized (PolymorphicEngine.class) {
                if (sInstance == null) {
                    sInstance = new PolymorphicEngine();
                }
            }
        }
        return sInstance;
    }

    /**
     * Inicializa a engine com contexto do app.
     */
    public void initialize(Context context) {
        this.appContext = context.getApplicationContext();
        try {
            this.androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            this.androidId = "fallback_" + System.currentTimeMillis();
        }
        this.currentKey = generateKey();
        this.currentStrategyIndex = secureRandom.nextInt(strategies.length);
        Log.d(TAG, "Engine inicializada - estratégia: " + currentStrategyIndex);
    }

    /**
     * Inicia o timer de re-keying automático.
     */
    public void start() {
        if (isRunning) return;
        isRunning = true;

        reKeyThread = new Thread(() -> {
            while (isRunning) {
                try {
                    // Intervalo aleatório: 30-120 segundos
                    long interval = 30000 + (long)(secureRandom.nextDouble() * 90000);
                    Thread.sleep(interval);

                    // Re-key: nova chave e nova estratégia
                    currentKey = generateKey();
                    currentStrategyIndex = secureRandom.nextInt(strategies.length);
                    Log.d(TAG, "Re-key: estratégia " + currentStrategyIndex);

                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "pe_rekey");
        reKeyThread.setDaemon(true);
        reKeyThread.setPriority(Thread.MIN_PRIORITY);
        reKeyThread.start();
    }

    public void stop() {
        isRunning = false;
        if (reKeyThread != null) reKeyThread.interrupt();
    }

    /**
     * Gera chave derivada do android_id + timestamp atual.
     * Chave muda a cada minuto (timestamp/60000).
     */
    public byte[] generateKey() {
        try {
            long timeSlot = System.currentTimeMillis() / 60000;
            String seed = androidId + "_" + timeSlot + "_" + secureRandom.nextLong();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(seed.getBytes("UTF-8"));
        } catch (Exception e) {
            byte[] fallback = new byte[32];
            secureRandom.nextBytes(fallback);
            return fallback;
        }
    }

    /**
     * Transforma payload com header polimórfico.
     * Output: [0x02][strategyId][keyHint 4 bytes][payload transformado]
     */
    public byte[] morphPayload(byte[] plainPayload) {
        if (plainPayload == null || plainPayload.length == 0) return plainPayload;

        try {
            TransformStrategy strategy = strategies[currentStrategyIndex];
            byte[] key = currentKey;
            byte[] encoded = strategy.encode(plainPayload, key);

            // Key hint = primeiros 4 bytes do hash da chave
            byte[] keyHint = new byte[4];
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keyHash = md.digest(key);
            System.arraycopy(keyHash, 0, keyHint, 0, 4);

            // Monta: [0x02][strategyId][keyHint 4B][encoded]
            ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + encoded.length);
            buffer.put(MORPH_HEADER);
            buffer.put(strategy.getStrategyId());
            buffer.put(keyHint);
            buffer.put(encoded);

            return buffer.array();
        } catch (Exception e) {
            Log.e(TAG, "morphPayload erro: " + e.getMessage());
            return plainPayload;
        }
    }

    /**
     * Decodifica payload polimórfico.
     */
    public byte[] demorphPayload(byte[] morphed) {
        if (morphed == null || morphed.length < 6) return morphed;

        try {
            if (morphed[0] != MORPH_HEADER) {
                return morphed; // Não é payload polimórfico
            }

            byte strategyId = morphed[1];
            byte[] encoded = new byte[morphed.length - 6];
            System.arraycopy(morphed, 6, encoded, 0, encoded.length);

            // Encontra estratégia pelo ID
            TransformStrategy strategy = findStrategy(strategyId);
            if (strategy == null) {
                Log.e(TAG, "Estratégia desconhecida: " + strategyId);
                return morphed;
            }

            return strategy.decode(encoded, currentKey);
        } catch (Exception e) {
            Log.e(TAG, "demorphPayload erro: " + e.getMessage());
            return morphed;
        }
    }

    /**
     * Transforma string para representação polimórfica em Base64.
     */
    public String morphString(String s) {
        if (s == null) return null;
        try {
            byte[] morphed = morphPayload(s.getBytes("UTF-8"));
            return Base64.encodeToString(morphed, Base64.NO_WRAP);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Decodifica string polimórfica de Base64.
     */
    public String demorphString(String morphed) {
        if (morphed == null) return null;
        try {
            byte[] data = Base64.decode(morphed, Base64.NO_WRAP);
            byte[] plain = demorphPayload(data);
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            return morphed;
        }
    }

    private TransformStrategy findStrategy(byte id) {
        for (TransformStrategy s : strategies) {
            if (s.getStrategyId() == id) return s;
        }
        return null;
    }

    public byte getCurrentStrategyId() {
        return strategies[currentStrategyIndex].getStrategyId();
    }

    public byte[] getCurrentKey() {
        return currentKey != null ? Arrays.copyOf(currentKey, currentKey.length) : new byte[0];
    }

    // ================= ESTRATÉGIA 1: XOR CHAIN =================

    private class XorChainTransform implements TransformStrategy {
        @Override
        public byte[] encode(byte[] data, byte[] key) {
            byte[] result = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                int keyIndex = i % key.length;
                result[i] = (byte)(data[i] ^ key[keyIndex] ^ (i > 0 ? result[i-1] : (byte)0xAA));
            }
            return result;
        }

        @Override
        public byte[] decode(byte[] encoded, byte[] key) {
            byte[] result = new byte[encoded.length];
            for (int i = 0; i < encoded.length; i++) {
                int keyIndex = i % key.length;
                result[i] = (byte)(encoded[i] ^ key[keyIndex] ^ (i > 0 ? encoded[i-1] : (byte)0xAA));
            }
            return result;
        }

        @Override
        public byte getStrategyId() { return 0x10; }
    }

    // ================= ESTRATÉGIA 2: ARITHMETIC SUBSTITUTION =================

    private class ArithmeticSubstitution implements TransformStrategy {
        // Inverso modular de 37 mod 256 = 97
        private static final int MULTIPLIER = 37;
        private static final int INVERSE = 97;
        private static final int ADDEND = 7;

        @Override
        public byte[] encode(byte[] data, byte[] key) {
            byte[] result = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                int keyByte = key[i % key.length] & 0xFF;
                int b = data[i] & 0xFF;
                result[i] = (byte)(((b + keyByte) * MULTIPLIER + ADDEND) & 0xFF);
            }
            return result;
        }

        @Override
        public byte[] decode(byte[] encoded, byte[] key) {
            byte[] result = new byte[encoded.length];
            for (int i = 0; i < encoded.length; i++) {
                int keyByte = key[i % key.length] & 0xFF;
                int b = encoded[i] & 0xFF;
                result[i] = (byte)(((b - ADDEND) * INVERSE - keyByte) & 0xFF);
            }
            return result;
        }

        @Override
        public byte getStrategyId() { return 0x20; }
    }

    // ================= ESTRATÉGIA 3: BYTE SHUFFLE =================

    private class ByteShuffleTransform implements TransformStrategy {
        @Override
        public byte[] encode(byte[] data, byte[] key) {
            if (data.length <= 1) return data.clone();

            byte[] result = data.clone();
            long seed = hashKey(key);
            java.util.Random rng = new java.util.Random(seed);

            // Fisher-Yates shuffle
            for (int i = result.length - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                byte tmp = result[i];
                result[i] = result[j];
                result[j] = tmp;
            }
            return result;
        }

        @Override
        public byte[] decode(byte[] encoded, byte[] key) {
            if (encoded.length <= 1) return encoded.clone();

            long seed = hashKey(key);
            java.util.Random rng = new java.util.Random(seed);

            // Gera mesma sequência de swaps
            int[] swapFrom = new int[encoded.length - 1];
            int[] swapTo = new int[encoded.length - 1];
            for (int i = encoded.length - 1; i > 0; i--) {
                swapFrom[encoded.length - 1 - i] = i;
                swapTo[encoded.length - 1 - i] = rng.nextInt(i + 1);
            }

            // Aplica swaps em ordem reversa
            byte[] result = encoded.clone();
            for (int k = swapFrom.length - 1; k >= 0; k--) {
                byte tmp = result[swapFrom[k]];
                result[swapFrom[k]] = result[swapTo[k]];
                result[swapTo[k]] = tmp;
            }
            return result;
        }

        @Override
        public byte getStrategyId() { return 0x30; }

        private long hashKey(byte[] key) {
            long h = 0;
            for (byte b : key) {
                h = h * 31 + (b & 0xFF);
            }
            return h;
        }
    }

    // ================= ESTRATÉGIA 4: SPLIT-MERGE =================

    private class SplitMergeTransform implements TransformStrategy {
        @Override
        public byte[] encode(byte[] data, byte[] key) {
            if (data.length < 4) return data.clone();

            int n = (key[0] & 0xFF) % 4 + 2; // 2-5 chunks
            if (n > data.length) n = 2;

            int chunkSize = data.length / n;
            int remainder = data.length % n;

            // Gera permutação determinística a partir da chave
            int[] perm = generatePermutation(n, key);

            // Divide em chunks e reordena
            byte[][] chunks = new byte[n][];
            int offset = 0;
            for (int i = 0; i < n; i++) {
                int size = chunkSize + (i < remainder ? 1 : 0);
                chunks[i] = new byte[size];
                System.arraycopy(data, offset, chunks[i], 0, size);
                offset += size;
            }

            // Monta output: [n 1 byte][perm n bytes][chunk sizes n*2 bytes][chunks reordenados]
            int totalSize = 1 + n + n * 2 + data.length;
            ByteBuffer buf = ByteBuffer.allocate(totalSize);
            buf.put((byte) n);

            // Salva permutação
            for (int p : perm) buf.put((byte) p);

            // Salva tamanhos originais
            for (byte[] chunk : chunks) {
                buf.putShort((short) chunk.length);
            }

            // Escreve chunks na ordem permutada
            for (int p : perm) {
                buf.put(chunks[p]);
            }

            return buf.array();
        }

        @Override
        public byte[] decode(byte[] encoded, byte[] key) {
            if (encoded.length < 3) return encoded.clone();

            try {
                ByteBuffer buf = ByteBuffer.wrap(encoded);
                int n = buf.get() & 0xFF;

                if (n < 2 || n > 5) return encoded;

                int[] perm = new int[n];
                for (int i = 0; i < n; i++) perm[i] = buf.get() & 0xFF;

                int[] sizes = new int[n];
                for (int i = 0; i < n; i++) sizes[i] = buf.getShort() & 0xFFFF;

                // Lê chunks na ordem permutada
                byte[][] permutedChunks = new byte[n][];
                for (int i = 0; i < n; i++) {
                    int origIndex = perm[i];
                    permutedChunks[i] = new byte[sizes[origIndex]];
                    buf.get(permutedChunks[i]);
                }

                // Reverte permutação
                byte[][] originalChunks = new byte[n][];
                for (int i = 0; i < n; i++) {
                    originalChunks[perm[i]] = permutedChunks[i];
                }

                // Merge
                int totalSize = 0;
                for (int s : sizes) totalSize += s;
                byte[] result = new byte[totalSize];
                int offset = 0;
                for (int i = 0; i < n; i++) {
                    if (originalChunks[i] != null) {
                        System.arraycopy(originalChunks[i], 0, result, offset, originalChunks[i].length);
                        offset += originalChunks[i].length;
                    }
                }

                return result;
            } catch (Exception e) {
                return encoded;
            }
        }

        @Override
        public byte getStrategyId() { return 0x40; }

        private int[] generatePermutation(int n, byte[] key) {
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) perm[i] = i;

            long seed = 0;
            for (int i = 0; i < Math.min(key.length, 8); i++) {
                seed = (seed << 8) | (key[i] & 0xFF);
            }
            java.util.Random rng = new java.util.Random(seed);

            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = perm[i];
                perm[i] = perm[j];
                perm[j] = tmp;
            }
            return perm;
        }
    }

    // ================= ESTRATÉGIA 5: NOISE INJECTION =================

    private class NoiseInjectionTransform implements TransformStrategy {
        @Override
        public byte[] encode(byte[] data, byte[] key) {
            // Calcula posições de injeção
            java.util.ArrayList<Integer> noisePositions = new java.util.ArrayList<>();
            int pos = 0;
            int ki = 0;
            while (pos < data.length) {
                int gap = (key[ki % key.length] & 0xFF) % 5 + 3; // gap de 3-7
                pos += gap;
                if (pos < data.length) {
                    noisePositions.add(pos);
                }
                ki++;
            }

            byte[] result = new byte[data.length + noisePositions.size()];
            int srcIdx = 0;
            int dstIdx = 0;
            int noiseIdx = 0;

            for (int i = 0; i < data.length; i++) {
                if (noiseIdx < noisePositions.size() && i == noisePositions.get(noiseIdx)) {
                    // Insere byte de ruído
                    result[dstIdx++] = (byte) secureRandom.nextInt(256);
                    noiseIdx++;
                }
                result[dstIdx++] = data[i];
            }

            // Prepend: [2 bytes contagem de noise]
            ByteBuffer buf = ByteBuffer.allocate(2 + dstIdx);
            buf.putShort((short) noisePositions.size());
            buf.put(result, 0, dstIdx);
            return buf.array();
        }

        @Override
        public byte[] decode(byte[] encoded, byte[] key) {
            if (encoded.length < 2) return encoded;

            try {
                ByteBuffer buf = ByteBuffer.wrap(encoded);
                int noiseCount = buf.getShort() & 0xFFFF;
                byte[] withNoise = new byte[encoded.length - 2];
                buf.get(withNoise);

                // Calcula mesmas posições
                int dataLen = withNoise.length - noiseCount;
                byte[] result = new byte[dataLen];

                // Recalcula posições de noise
                java.util.HashSet<Integer> noisePositions = new java.util.HashSet<>();
                int pos = 0;
                int ki = 0;
                int foundNoise = 0;

                // Calcula posições baseado no tamanho original dos dados
                int tempPos = 0;
                int tempKi = 0;
                int noiseInserted = 0;
                java.util.ArrayList<Integer> noisePosInOutput = new java.util.ArrayList<>();

                // Recalcula posições no array de saída (com noise)
                int outputPos = 0;
                int dataPos = 0;
                tempKi = 0;
                while (dataPos < dataLen && foundNoise < noiseCount) {
                    int gap = (key[tempKi % key.length] & 0xFF) % 5 + 3;
                    tempKi++;

                    if (dataPos + gap < dataLen) {
                        outputPos += gap;
                        dataPos += gap;
                        noisePosInOutput.add(outputPos);
                        outputPos++; // pula o noise
                        foundNoise++;
                    } else {
                        break;
                    }
                }

                // Remove bytes de ruído
                java.util.HashSet<Integer> noiseSet = new java.util.HashSet<>(noisePosInOutput);
                int rIdx = 0;
                for (int i = 0; i < withNoise.length && rIdx < dataLen; i++) {
                    if (!noiseSet.contains(i)) {
                        result[rIdx++] = withNoise[i];
                    }
                }

                return result;
            } catch (Exception e) {
                return encoded;
            }
        }

        @Override
        public byte getStrategyId() { return 0x50; }
    }

    // ================= ESTRATÉGIA 6: MULTI-LAYER =================

    private class MultiLayerTransform implements TransformStrategy {
        @Override
        public byte[] encode(byte[] data, byte[] key) {
            // Determina quantas camadas (2-3) e quais strategies usar
            long seed = 0;
            for (int i = 0; i < Math.min(key.length, 8); i++) {
                seed = (seed << 8) | (key[i] & 0xFF);
            }
            java.util.Random rng = new java.util.Random(seed);

            int numLayers = 2 + rng.nextInt(2); // 2 ou 3
            // Usa apenas strategies 0-4 (evita recursão com MultiLayer)
            int[] layerIds = new int[numLayers];
            for (int i = 0; i < numLayers; i++) {
                layerIds[i] = rng.nextInt(5); // 0-4, sem multi-layer
            }

            // Aplica layers em sequência
            byte[] result = data;
            for (int id : layerIds) {
                result = strategies[id].encode(result, key);
            }

            // Header: [numLayers 1B][layerIds...]
            ByteBuffer buf = ByteBuffer.allocate(1 + numLayers + result.length);
            buf.put((byte) numLayers);
            for (int id : layerIds) buf.put((byte) id);
            buf.put(result);
            return buf.array();
        }

        @Override
        public byte[] decode(byte[] encoded, byte[] key) {
            if (encoded.length < 2) return encoded;

            try {
                ByteBuffer buf = ByteBuffer.wrap(encoded);
                int numLayers = buf.get() & 0xFF;
                if (numLayers < 2 || numLayers > 3) return encoded;

                int[] layerIds = new int[numLayers];
                for (int i = 0; i < numLayers; i++) {
                    layerIds[i] = buf.get() & 0xFF;
                }

                byte[] result = new byte[encoded.length - 1 - numLayers];
                buf.get(result);

                // Aplica decode em ordem reversa
                for (int i = numLayers - 1; i >= 0; i--) {
                    if (layerIds[i] < 5) {
                        result = strategies[layerIds[i]].decode(result, key);
                    }
                }

                return result;
            } catch (Exception e) {
                return encoded;
            }
        }

        @Override
        public byte getStrategyId() { return 0x60; }
    }
}
