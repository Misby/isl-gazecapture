package com.iai.mdf.Handlers;


import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.PermissionChecker;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import com.iai.mdf.Activities.DataCollectionActivity;
import com.iai.mdf.DependenceClasses.DeviceConfiguration;
import com.iai.mdf.Fragments.FragmentDataCollectionByVideo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by Mou on 9/12/2017.
 */

public class CameraHandler {

    public static final int CAMERA_STATE_IDLE = 0;
    public static final int CAMERA_STATE_PREVIEW = 1;
    public static final int CAMERA_STATE_STILL_CAPTURE = 2;
    private final String LOG_TAG = "CameraHandler";
    private final int IMAGE_FORMAT = ImageFormat.JPEG;      //if changed, modify capureBuilder.set(Orientation, ) accordingly


    private static CameraHandler myInstance;
    private Context         ctxt;
    private CameraManager   cameraManager;
    private CameraDevice    frontCamera;
    private int             cameraState;
    Range<Integer> controlAECompensationRange;
    private Semaphore cameraOpenCloseLock = new Semaphore(1);





    // private constructor
    public CameraHandler(Context context, boolean isPreviewUsed) {
        this.ctxt = context;
        cameraManager = (CameraManager) ctxt.getSystemService(Context.CAMERA_SERVICE);
        if( isPreviewUsed ) {
            imageReaderForPrev = ImageReader.newInstance(
                    DataCollectionActivity.Image_Size.getHeight(),
                    DataCollectionActivity.Image_Size.getWidth(),
                    ImageFormat.YUV_420_888,
                    10
            );
        } else {
            imageReaderForPrev = null;
            imageFileHandler = new ImageFileHandler(context);
        }
        frontCamera = null;
        cameraState = CAMERA_STATE_IDLE;
    }

    // to socketCreate a CameraHandler Singleton
//    public static synchronized CameraHandler getInstance(Context context, boolean isPreviewUsed) {
//        if (null == myInstance) {
//            myInstance = new CameraHandler(context, isPreviewUsed);
//        }
//        return myInstance;
//    }


    private String getFrontCameraId() {
        String frontCameraId = "unknown";
        try {
            for (final String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                controlAECompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                int cameraOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (cameraOrientation == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = cameraId;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.d(LOG_TAG, "Front camera ID: " + frontCameraId);
        return frontCameraId;
    }

    private int getOrientation() {
//        int rotation = ((Activity) this.ctxt).getWindowManager().getDefaultDisplay().getRotation();
//        SparseIntArray ORIENTATIONS = new SparseIntArray();
//        ORIENTATIONS.append(Surface.ROTATION_0, 180);
//        ORIENTATIONS.append(Surface.ROTATION_90, 180);
//        ORIENTATIONS.append(Surface.ROTATION_180, 90);
//        ORIENTATIONS.append(Surface.ROTATION_270, 0);
//        int degree = 0;
//        if(Build.MODEL.equalsIgnoreCase("Nexus 6P")){
//            degree = 180;
//        }
        return DeviceConfiguration.getInstance(ctxt).getImageRotation();
    }



    /*************** Data Collection (Deprecated) ***************/

    // background capture
    private ImageFileHandler            imageFileHandler;
    private Size                        savedImageSize;
    private CameraDevice.StateCallback  stateCallbackForImage;
    private CaptureRequest.Builder      captureBuilderForImage;


    public void openFrontCameraForDataCollection() {
        Log.d(LOG_TAG, "Try to open local camera");
        stateCallbackForImage = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                //when camera is open, initilize imageFileHandler for saving the pic
                Log.d(LOG_TAG, "Camera " + camera.getId() + " is opened");
                cameraOpenCloseLock.release();
                frontCamera = camera;
            }

            @Override
            public void onClosed(@NonNull CameraDevice camera) {
                Log.d(LOG_TAG, "Camera " + camera.getId() + " is closed");
                frontCamera = null;
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.d(LOG_TAG, "Camera " + camera.getId() + " is disconnected");
                camera.close();
                frontCamera = null;
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.d(LOG_TAG, "Camera " + camera.getId() + " can\'t be opened with the error number " + error);
                frontCamera = null;
            }
        };
        try {
            String frontCameraId = getFrontCameraId();
            if (Build.VERSION.SDK_INT > 22) {
                if (this.ctxt.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        && this.ctxt.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    if( !cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MICROSECONDS) ){
                        throw new RuntimeException("Time out waiting to lock opening");
                    }
                    this.cameraManager.openCamera(frontCameraId, this.stateCallbackForImage, new Handler());
                } else {
                    Log.d(LOG_TAG, "Can\'t open camera because of no permission");
                    Toast.makeText(this.ctxt, "Can't open camera because of no permission", Toast.LENGTH_SHORT);
                }
            } else {
                if (PermissionChecker.checkCallingOrSelfPermission(this.ctxt, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        && PermissionChecker.checkCallingOrSelfPermission(this.ctxt, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    if( !cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MICROSECONDS) ){
                        throw new RuntimeException("Time out waiting to lock opening");
                    }
                    this.cameraManager.openCamera(frontCameraId, this.stateCallbackForImage, null);
                } else {
                    Log.d(LOG_TAG, "Can\'t open camera because of no permission");
                    Toast.makeText(this.ctxt, "Can't open camera because of no permission", Toast.LENGTH_SHORT);
                }
            }
        } catch (final CameraAccessException e) {
            Log.e(LOG_TAG, "exception occurred while opening camera with errors: ", e);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void closeFrontCameraForDataCollection() {
        Log.d(LOG_TAG, "Try to close front camera");
        try {
            cameraOpenCloseLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            cameraOpenCloseLock.release();
        }
        if (null != frontCamera) {
            frontCamera.close();
            Log.d(LOG_TAG, "Camera " + frontCamera.getId() + " is closed");
            frontCamera = null;
            savedImageSize = null;
        }
    }

    public void setImageSize(Size _size){
        savedImageSize = _size;
        imageFileHandler.setImageFormat(IMAGE_FORMAT);
        imageFileHandler.setImageSize(savedImageSize);
        imageFileHandler.instantiateImageReader();
    }

    public void takePicture(Point point) {
        if (null == frontCamera) {
            Log.d(LOG_TAG, "No front camera");
            return;
        }
        if (null == imageFileHandler.getImageReader()) {
            Log.d(LOG_TAG, "ImageReader is not ready, can\'t take picture.");
            return;
        }
        if( null==savedImageSize ){
            Log.d(LOG_TAG, "Image size should be assigned, can\'t take picture.");
            return;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        String picName = timestamp + "_" + point.x + "_" + point.y;
        Log.d(LOG_TAG, picName);
        imageFileHandler.setImageName(picName);
        try {
            final List<Surface> outputSurfaces = new ArrayList<>();
            outputSurfaces.add(imageFileHandler.getImageReader().getSurface());
            frontCamera.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                captureBuilderForImage = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                captureBuilderForImage.addTarget(imageFileHandler.getImageReader().getSurface());
                                captureBuilderForImage.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                captureBuilderForImage.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
                                captureBuilderForImage.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                                captureBuilderForImage.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ);
                                captureBuilderForImage.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR);
                                captureBuilderForImage.set(CaptureRequest.JPEG_ORIENTATION, getOrientation());
                                session.capture(captureBuilderForImage.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(LOG_TAG, " exception occurred while accessing " + frontCamera.getId(), e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        }
                    }
                    , null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void setSavingCallback(ImageFileHandler.SavingCallback savingCallback) {
        imageFileHandler.setSavingCallback(savingCallback);
    }

    public void deleteLastPicture() {
        this.imageFileHandler.deleteLastImage();
    }




    /******************* Preview ********************/

    private TextureView     textureViewForPrev;
    private SurfaceTexture  surfaceTextureForPrev;
    private Size            imageSizeForPrev;
    private Handler         handlerForPrev;
    private CameraDevice    frontCameraForPrev;
    private Semaphore       cameraOpenCloseLockForPrev = new Semaphore(1);
    private CaptureRequest.Builder  captureBuilderForPrev;
    private ImageReader     imageReaderForPrev;
    private boolean         isImageAvailableListenerForPrevSet = false;

    public void startPreview(TextureView textureView) {
        cameraManager = (CameraManager) ctxt.getSystemService(Context.CAMERA_SERVICE);
        HandlerThread handlerThread = new HandlerThread("Gaze_DataCollection_Preview");
        handlerThread.start();
        handlerForPrev = new Handler(handlerThread.getLooper());
        textureViewForPrev = textureView;
        textureViewForPrev.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                initCameraAndPreview(width,height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    public void stopPreview(){
        Log.d(LOG_TAG, "Try to close front camera for preview");
        try {
            cameraOpenCloseLockForPrev.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            cameraOpenCloseLockForPrev.release();
        }
        if (null != frontCameraForPrev) {
            Log.d(LOG_TAG, "Camera " + frontCameraForPrev.getId() + " is closed");
            frontCameraForPrev.close();
            frontCameraForPrev = null;
        }
        handlerForPrev = null;
        cameraState = CAMERA_STATE_IDLE;
    }

    public void setOnImageAvailableListenerForPrev(ImageReader.OnImageAvailableListener listener){
        imageReaderForPrev.setOnImageAvailableListener(listener, null);
        isImageAvailableListenerForPrevSet = true;
    }

    public void setCameraState(int state){
        cameraState = state;
    }

    public int getCameraState(){
        return cameraState;
    }

    private void initCameraAndPreview(int width, int height) {
        Log.d(LOG_TAG, "init camera and preview");
        try {
            String frontCameraId = getFrontCameraId();
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(frontCameraId);
            StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            imageSizeForPrev = getPreferredPreviewSize(map.getOutputSizes(SurfaceTexture.class), height, width);    // intentionally switch the height and width
            if (ActivityCompat.checkSelfPermission(ctxt, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.d(LOG_TAG, "No permission for local camera");
                ((Activity) ctxt).finish();
                return;
            }
            if( !cameraOpenCloseLockForPrev.tryAcquire(2500, TimeUnit.MICROSECONDS) ){
                throw new RuntimeException("Time out waiting to lock opening");
            }
            cameraManager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    Log.d(LOG_TAG,"Front camera is opened.");
                    cameraOpenCloseLockForPrev.release();
                    frontCameraForPrev = camera;
                    cameraState = CAMERA_STATE_PREVIEW;
                    createCameraCaptureSession();
                }

                @Override
                public void onClosed(@NonNull CameraDevice camera) {
                    super.onClosed(camera);
                    frontCameraForPrev = null;
                    cameraState = CAMERA_STATE_IDLE;
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    frontCameraForPrev = camera;
                    cameraState = CAMERA_STATE_IDLE;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    frontCameraForPrev = camera;
                    cameraState = CAMERA_STATE_IDLE;
                }
            }, handlerForPrev);
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, "open camera failed." + e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void createCameraCaptureSession(){
        Log.d(LOG_TAG,"createCameraCaptureSession");
        if( null!=imageReaderForPrev && !isImageAvailableListenerForPrevSet){
            Log.d(LOG_TAG, "OnImageAvailableListener should be assigned specifically for Image Upload, can\'t upload picture.");
            return;
        }
        try {
            final List<Surface> outputSurfaces = new ArrayList<>();
            surfaceTextureForPrev = textureViewForPrev.getSurfaceTexture();
            surfaceTextureForPrev.setDefaultBufferSize(imageSizeForPrev.getWidth(), imageSizeForPrev.getHeight());
            Surface previewSurface = new Surface(surfaceTextureForPrev);
            captureBuilderForPrev = frontCameraForPrev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureBuilderForPrev.addTarget(previewSurface);
            outputSurfaces.add(previewSurface);
            if( null!=imageReaderForPrev ){
                captureBuilderForPrev.addTarget(imageReaderForPrev.getSurface());
                outputSurfaces.add(imageReaderForPrev.getSurface());
            }
            frontCameraForPrev.createCaptureSession(
                    outputSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                                    if( frontCamera!=null ){
                                        return;
                                    }
                                    captureBuilderForPrev.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                    captureBuilderForPrev.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
//                                    captureBuilderForPrev.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 3);
                                    captureBuilderForPrev.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY);
                                    captureBuilderForPrev.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE);
                                    try {
                                        session.setRepeatingRequest(captureBuilderForPrev.build(), sessionCaptureCallbackForPrev, handlerForPrev);
                                    } catch (CameraAccessException e) {
                                        e.printStackTrace();
                                        Log.d(LOG_TAG,"set preview builder failed."+e.getMessage());
                                    }
                                }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                        }
                    }, handlerForPrev);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.CaptureCallback sessionCaptureCallbackForPrev =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                    super.onCaptureProgressed(session, request, partialResult);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                }
            };

    private Size getPreferredPreviewSize(Size[] mapSizes, int width, int height) {
        double EPSL = 0.00001;
        List<Size> collectorSizes = new ArrayList<>();
        // looking for the exact size or the one with the exact ratio;
        double preferredRatio = (double) width / height;
        for(Size option : mapSizes) {
            if( width==option.getWidth() && height==option.getHeight() ){
                return option;
            }
            double curRatio = (double)option.getWidth()/option.getHeight();
            if( Math.abs(preferredRatio-curRatio) < EPSL) {
                collectorSizes.add(option);
            }
        }
        if( collectorSizes.size()==0 ){ // if no size with the exact ratio
            double minRatioDiff = 1000;
            Size bestOption = null;
            for(Size option : mapSizes) {
                double curRatio = (double)option.getWidth()/option.getHeight();
                if( Math.abs(curRatio-preferredRatio) < minRatioDiff ){
                    bestOption = option;
                }
            }
            return bestOption;
        }
//        return new Size(800, 600);
        return Collections.max(collectorSizes, new Comparator<Size>() {
            @Override
            public int compare(Size lhs, Size rhs) {
                return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
            }
        });
    }




    /******************* Video ********************/

    private CameraDevice.StateCallback stateCallbackForVideo;
    private CaptureRequest.Builder  captureBuilderForVideo;
    private static final String APP_FOLDER_NAME = "Android_Gaze_Data";
    private int         VIDEO_FPS = 30;
    private MediaRecorder mediaRecorder;
    private boolean     isVideoing = false;
    private String      subFolderName;
    private String      videoName;


    public CameraHandler(Context context) {
        this.ctxt = context;
        cameraManager = (CameraManager) ctxt.getSystemService(Context.CAMERA_SERVICE);
        frontCamera = null;
    }

    public void setVideoCollectionFPS(int videoFPS){
        VIDEO_FPS = videoFPS;
    }

    public void openFrontCameraForVideo() {
        Log.d(LOG_TAG, "Try to open local camera");
        stateCallbackForVideo = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                //when camera is open, initilize imageFileHandler for saving the pic
                Log.d(LOG_TAG, "Camera " + camera.getId() + " is opened");
                cameraOpenCloseLock.release();
                frontCamera = camera;
                initMediaRecorder();
            }

            @Override
            public void onClosed(@NonNull CameraDevice camera) {
                Log.d(LOG_TAG, "Camera " + camera.getId() + " is closed");
                frontCamera = null;
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.d(LOG_TAG, "Camera " + camera.getId() + " is disconnected");
                camera.close();
                frontCamera = null;
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.d(LOG_TAG, "Camera " + camera.getId() + " can\'t be opened with the error number " + error);
                frontCamera = null;
            }
        };
        try {
            String frontCameraId = getFrontCameraId();
            if (Build.VERSION.SDK_INT > 22) {
                if (this.ctxt.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        && this.ctxt.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    if( !cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MICROSECONDS) ){
                        throw new RuntimeException("Time out waiting to lock opening");
                    }
                    this.cameraManager.openCamera(frontCameraId, this.stateCallbackForVideo, new Handler());
                } else {
                    Log.d(LOG_TAG, "Can\'t open camera because of no permission");
                    Toast.makeText(this.ctxt, "Can't open camera because of no permission", Toast.LENGTH_SHORT);
                }
            } else {
                if (PermissionChecker.checkCallingOrSelfPermission(this.ctxt, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        && PermissionChecker.checkCallingOrSelfPermission(this.ctxt, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    if( !cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MICROSECONDS) ){
                        throw new RuntimeException("Time out waiting to lock opening");
                    }
                    this.cameraManager.openCamera(frontCameraId, this.stateCallbackForVideo, null);
                } else {
                    Log.d(LOG_TAG, "Can\'t open camera because of no permission");
                    Toast.makeText(this.ctxt, "Can't open camera because of no permission", Toast.LENGTH_SHORT);
                }
            }
        } catch (final CameraAccessException e) {
            Log.e(LOG_TAG, "exception occurred while opening camera with errors: ", e);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void closeFrontCameraForVideo() {
        Log.d(LOG_TAG, "Try to close front camera");
        try {
            cameraOpenCloseLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            cameraOpenCloseLock.release();
        }
        if (null != frontCamera) {
            frontCamera.close();
            Log.d(LOG_TAG, "Camera " + frontCamera.getId() + " is closed");
            frontCamera = null;
            savedImageSize = null;
        }
    }

    public void initMediaRecorder(){
        if( frontCamera==null ){
            Log.d(LOG_TAG, "Front Camera is Null");
            return;
        }
        mediaRecorder = new MediaRecorder();
//        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
//        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

        Surface mSurface = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            mSurface = MediaCodec.createPersistentInputSurface();
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
//        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
//        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM);
        mediaRecorder.setVideoSize(640, 480);// resolution
        mediaRecorder.setVideoFrameRate(VIDEO_FPS);    //frame rate
        mediaRecorder.setVideoEncodingBitRate(24 * 1024 * 1024);//
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);//视频编码格式
//        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.VP8);//视频编码格式
        mediaRecorder.setOrientationHint(DeviceConfiguration.getInstance(ctxt).getImageRotation());//输出视频播放的方向提示
//        //设置记录会话的最大持续时间（毫秒）
//        mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_480P));
    }

    public boolean isVideoing(){
        return  isVideoing;
    }

    public void startVideo(){
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd");
        String timeDate = sdf.format(new Date());
        subFolderName = timeDate;
        File videoFolder = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), APP_FOLDER_NAME + File.separator + subFolderName);
        if (!videoFolder.exists()) {
            if (!videoFolder.mkdirs()) {
                Log.d(LOG_TAG, "failed to socketCreate directory");
            }
        }
        sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        videoName = sdf.format(new Date());
        mediaRecorder.setOutputFile(videoFolder.getAbsoluteFile() + File.separator + videoName + ".mp4");
        try {
            mediaRecorder.prepare();
            List<Surface> list = new ArrayList<>();
            list.add(mediaRecorder.getSurface());
            captureBuilderForVideo = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            frontCamera.createCaptureSession(list, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        captureBuilderForVideo.addTarget(mediaRecorder.getSurface());
                        captureBuilderForVideo.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        captureBuilderForVideo.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        captureBuilderForVideo.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
//                        captureBuilderForVideo.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ);
                        captureBuilderForVideo.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR);
                        session.setRepeatingRequest(captureBuilderForVideo.build(), null, null);
                    } catch (CameraAccessException e) {
                        Log.e(LOG_TAG, " exception occurred while accessing " + frontCamera.getId(), e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.d(LOG_TAG, "Create CaptureSession failed when trying to take video");
                }
            }, null);
            mediaRecorder.start();
            isVideoing = true;
        } catch (CameraAccessException e){
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopVideo(){
        Toast.makeText(ctxt, "Video is Saved in " + videoName + ".mp4", Toast.LENGTH_SHORT).show();
        if( mediaRecorder!=null ) {
            isVideoing = false;
            try{
                mediaRecorder.stop();
            }catch(RuntimeException stopException){
                //handle cleanup here
                Log.d("CameraHandler", "Stopping Error");
            }
            mediaRecorder.reset();
        }
    }

    public void releaseResource(){
        closeFrontCameraForVideo();
        if( mediaRecorder!=null ) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    public void recordDotPosition(Point point){
        File dotRecordFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES),
                CameraHandler.APP_FOLDER_NAME + File.separator + subFolderName + File.separator + videoName + ".dat");
        try {
            FileOutputStream outputStream = new FileOutputStream(dotRecordFile, true);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            if( point==null ) {
                outputStreamWriter.append(String.valueOf(VIDEO_FPS * FragmentDataCollectionByVideo.DOT_DURATION_IN_SEC) + "\n");
            } else {
                outputStreamWriter.append(String.valueOf(point.x) + " " + String.valueOf(point.y) + "\n");
            }
            outputStreamWriter.close();
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Exception occurred while recording the dots", e);
        }
    }






}
