/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soundcloud.android.crop;

import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;

import com.soundcloud.android.crop.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/*
 * Modified from original in AOSP.
 */
public class CropImageActivity extends MonitoredActivity {

    private static final int MAX_TEXTURE_SIZE = 2000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final boolean IN_MEMORY_CROP = Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD_MR1;

    private final Handler mHandler = new Handler();

    private int mAspectX;
    private int mAspectY;
    private int sampleSize = 1;

    // Output image size
    private int mMaxX;
    private int mMaxY;
    private int mExifRotation;

    private Uri mSourceUri;
    private Uri mSaveUri;

    private boolean mIsSaving; // When the save button has been clicked

    private RotateBitmap mRotateBitmap;
    private CropImageView mImageView;
    private HighlightView mCrop;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.crop__activity_crop);
        initViews();

        setupFromIntent();
        if (mRotateBitmap == null) {
            finish();
            return;
        }
        startCrop();
    }

    private void initViews() {
        mImageView = (CropImageView) findViewById(R.id.crop_image);
        mImageView.mContext = this;
        mImageView.setRecycler(new ImageViewTouchBase.Recycler() {
            @Override
            public void recycle(Bitmap b) {
                b.recycle();
                System.gc();
            }
        });

        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        findViewById(R.id.btn_done).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onSaveClicked();
            }
        });
    }

    private void setupFromIntent() {
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();

        if (extras != null) {
            mAspectX = extras.getInt(Crop.Extra.ASPECT_X);
            mAspectY = extras.getInt(Crop.Extra.ASPECT_Y);
            mMaxX = extras.getInt(Crop.Extra.MAX_X);
            mMaxY = extras.getInt(Crop.Extra.MAX_Y);
            mSaveUri = extras.getParcelable(MediaStore.EXTRA_OUTPUT);
        }

        sampleSize = 1;
        mSourceUri = intent.getData();
        if (mSourceUri != null) {
            mExifRotation = CropUtil.getExifRotation(CropUtil.getFromMediaUri(getContentResolver(), mSourceUri));
            try {
                mRotateBitmap = loadInSampleSize();
            } catch (IOException ioe) {
                setResultException(ioe);
            } catch (OutOfMemoryError e) {
                setResultException(e);
            }
        }
    }

    private RotateBitmap loadInSampleSize() throws IOException {
        InputStream is = null;
        try {
            is = getContentResolver().openInputStream( mSourceUri );
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleSize;
            Bitmap b = BitmapFactory.decodeStream( is, null, o );
            if(b == null || b.getWidth() > MAX_TEXTURE_SIZE || b.getHeight() > MAX_TEXTURE_SIZE) {
                if(b != null) {
                    b.recycle();
                }
                throw new OutOfMemoryError( );
            }
            return new RotateBitmap(b, mExifRotation );
        } catch (OutOfMemoryError oom) {
            if(sampleSize == 16) {
                throw oom;
            } else {
                sampleSize = sampleSize*2;
                return loadInSampleSize();
            }
        } finally {
            CropUtil.closeSilently(is);
        }
    }

    private void startCrop() {
        if (isFinishing()) {
            return;
        }
        mImageView.setImageRotateBitmapResetBase(mRotateBitmap, true);
        CropUtil.startBackgroundJob(this, null, getResources().getString(R.string.crop__wait),
                new Runnable() {
                    public void run() {
                        final CountDownLatch latch = new CountDownLatch(1);
                        mHandler.post(new Runnable() {
                            public void run() {
                                if (mImageView.getScale() == 1F) {
                                    mImageView.center(true, true);
                                }
                                latch.countDown();
                            }
                        });
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        new Cropper().crop();
                    }
                }, mHandler);
    }

    private class Cropper {

        private void makeDefault() {
            if (mRotateBitmap == null) return;

            HighlightView hv = new HighlightView(mImageView);
            final int width  = mRotateBitmap.getWidth();
            final int height = mRotateBitmap.getHeight();

            Rect imageRect = new Rect(0, 0, width, height);

            // Make the default size about 4/5 of the width or height
            int cropWidth = Math.min(width, height) * 4 / 5;
            @SuppressWarnings("SuspiciousNameCombination")
            int cropHeight = cropWidth;

            if (mAspectX != 0 && mAspectY != 0) {
                if (mAspectX > mAspectY) {
                    cropHeight = cropWidth * mAspectY / mAspectX;
                } else {
                    cropWidth = cropHeight * mAspectX / mAspectY;
                }
            }

            int x = (width - cropWidth) / 2;
            int y = (height - cropHeight) / 2;

            RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
            hv.setup(mImageView.getUnrotatedMatrix(), imageRect, cropRect, mAspectX != 0 && mAspectY != 0);
            mImageView.add(hv);
        }

        public void crop() {
            mHandler.post(new Runnable() {
                public void run() {
                    makeDefault();
                    mImageView.invalidate();
                    if (mImageView.mHighlightViews.size() == 1) {
                        mCrop = mImageView.mHighlightViews.get(0);
                        mCrop.setFocus(true);
                    }
                }
            });
        }
    }

    /*
     * TODO
     * This should use the decode/crop/encode single step API so that the whole
     * (possibly large) Bitmap doesn't need to be read into memory
     */
    private void onSaveClicked() {
        if (mCrop == null || mIsSaving) {
            return;
        }
        mIsSaving = true;
        Bitmap croppedImage = null;
        Rect r = mCrop.getCropRect();
        int width = r.width();
        int height = r.height();

        int outWidth = width, outHeight = height;
        if (mMaxX > 0 && mMaxY > 0 && (width > mMaxX || height > mMaxY)) {
            float ratio = (float) width / (float) height;
            if ((float) mMaxX / (float) mMaxY > ratio) {
                outHeight = mMaxY;
                outWidth = (int) ((float) mMaxY * ratio + .5f);
            } else {
                outWidth = mMaxX;
                outHeight = (int) ((float) mMaxX / ratio + .5f);
            }
        }
        if (IN_MEMORY_CROP && mRotateBitmap != null) {
            croppedImage = inMemoryCrop(mRotateBitmap, croppedImage, r,
                                        width*sampleSize,
                                        height*sampleSize,
                                        outWidth*sampleSize,
                                        outHeight*sampleSize);
            if (croppedImage != null) {
                mImageView.setImageBitmapResetBase(croppedImage, true);
                mImageView.center(true, true);
                mImageView.mHighlightViews.clear();
            }
        } else {
            try {
                croppedImage = decodeRegionCrop(croppedImage, r);
            } catch (IllegalArgumentException e) {
                setResultException(e);
                finish();
                return;
            }

            if (croppedImage != null) {
                mImageView.setImageRotateBitmapResetBase(new RotateBitmap(croppedImage, mExifRotation), true);
                mImageView.center(true, true);
                mImageView.mHighlightViews.clear();
            }
        }
        if(croppedImage.getWidth() > mMaxX || croppedImage.getHeight() > mMaxY) {
            croppedImage = getResizedBitmap(croppedImage, mMaxX, mMaxY);
        }

        saveImage(croppedImage);
    }

    private Bitmap getResizedBitmap(Bitmap bm, int newHeight, int newWidth) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        return Bitmap.createBitmap(bm, 0, 0, width, height, matrix, false);
    }

    private void saveImage(final Bitmap croppedImage) {

        if (croppedImage != null) {
            CropUtil.startBackgroundJob(this, null, getResources().getString(R.string.crop__saving),
                    new Runnable() {
                        public void run() {
                            saveOutput(croppedImage);
                        }
                    }, mHandler );
        } else {
            finish();
        }
    }

    @TargetApi(10)
    private Bitmap decodeRegionCrop(Bitmap croppedImage, Rect rect) {
        // Release memory now
        clearImageView();

        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(mSourceUri);
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(is, false);
            final int width  = decoder.getWidth();
            final int height = decoder.getHeight();

            if (mExifRotation != 0) {
                // Adjust crop area to account for image rotation
                Matrix matrix = new Matrix();
                matrix.setRotate(-mExifRotation);
                rect = new Rect((int) rect.left*sampleSize,
                                (int) rect.top*sampleSize,
                                (int) rect.right*sampleSize,
                                (int) rect.bottom*sampleSize);
                RectF adjusted = new RectF();
                matrix.mapRect(adjusted, new RectF(rect));
                // Adjust to account for origin at 0,0
                adjusted.offset(adjusted.left < 0 ? width : 0, adjusted.top < 0 ? height : 0);
                rect = new Rect((int) adjusted.left,
                                (int) adjusted.top,
                                (int) adjusted.right,
                                (int) adjusted.bottom);
            } else {
                rect = new Rect((int) rect.left*sampleSize,
                                (int) rect.top*sampleSize,
                                (int) rect.right*sampleSize,
                                (int) rect.bottom*sampleSize);
            }

            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleSize;
            try {
                croppedImage = decoder.decodeRegion( rect, o );
            } catch (OutOfMemoryError oom) {
                setResultException( oom );
            } catch (IllegalArgumentException e) {
                // Rethrow with some extra information
                throw new IllegalArgumentException("Rectangle " + rect + " is outside of the image ("
                        + width + "," + height + "," + mExifRotation + ")", e);
            }
        } catch (IOException e) {
            Log.e("Error cropping picture: " + e.getMessage(), e);
            finish();
        } finally {
            CropUtil.closeSilently(is);
        }
        return croppedImage;
    }

    private Bitmap inMemoryCrop(RotateBitmap rotateBitmap, Bitmap croppedImage, Rect r,
                                int width, int height, int outWidth, int outHeight) {
        // In-memory crop means potential OOM errors,
        // but we have no choice as we can't selectively decode a bitmap with this API level
        System.gc();

        try {
            croppedImage = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565);

            Canvas canvas = new Canvas(croppedImage);
            RectF dstRect = new RectF(0, 0, width, height);

            Matrix m = new Matrix();
            m.setRectToRect(new RectF(r), dstRect, Matrix.ScaleToFit.FILL);
            m.preConcat(rotateBitmap.getRotateMatrix());
            canvas.drawBitmap(rotateBitmap.getBitmap(), m, null);

        } catch (OutOfMemoryError e) {
            Log.e("Error cropping picture: " + e.getMessage(), e);
            System.gc();
        }

        // Release bitmap memory as soon as possible
        clearImageView();
        return croppedImage;
    }

    private void clearImageView() {
        mImageView.clear();
        if (mRotateBitmap != null) {
            mRotateBitmap.recycle();
        }
        System.gc();
    }

    private void saveOutput(final Bitmap croppedImage) {
        if (mSaveUri != null) {
            OutputStream outputStream = null;
            try {
                outputStream = getContentResolver().openOutputStream(mSaveUri);
                if (outputStream != null) {
                    if(mExifRotation != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate( mExifRotation );
                        Bitmap rotated = Bitmap.createBitmap( croppedImage, 0, 0, croppedImage.getWidth(),
                                                              croppedImage.getHeight(), matrix, true );
                        rotated.compress( Bitmap.CompressFormat.JPEG, 90, outputStream );
                    } else {
                        croppedImage.compress( Bitmap.CompressFormat.JPEG, 90, outputStream );
                    }
                }

            } catch (IOException e) {
                setResultException(e);
                Log.e("Cannot open file: " + mSaveUri, e);
            } finally {
                CropUtil.closeSilently(outputStream);
            }
            setResultUri( mSaveUri );
        }
        mHandler.post(new Runnable() {
            public void run() {
                mImageView.clear();
                croppedImage.recycle();
            }
        });
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRotateBitmap != null) {
            mRotateBitmap.recycle();
        }
    }

    @Override
    public boolean onSearchRequested() {
        return false;
    }

    public boolean isSaving() {
        return mIsSaving;
    }

    private void setResultUri(Uri uri) {
        setResult(RESULT_OK, new Intent().putExtra(MediaStore.EXTRA_OUTPUT, uri));
    }

    private void setResultException(Throwable throwable){
        setResult(Crop.RESULT_ERROR, new Intent().putExtra(Crop.Extra.ERROR, throwable));
    }

}

