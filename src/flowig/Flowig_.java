package flowig;

//############################################################################
// java
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.awt.image.BufferedImage;
import java.awt.Rectangle;

//############################################################################
// imagej
import ij.plugin.PlugIn;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.process.ImageProcessor;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.gui.Arrow;
import ij.gui.ImageRoi;
import ij.gui.PolygonRoi;
import ij.io.DirectoryChooser;
import ij.process.ColorProcessor;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.RasterFormatException;
import java.awt.image.WritableRaster;

//############################################################################
// opencv
import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_imgproc;
import org.bytedeco.javacpp.opencv_optflow;
import org.bytedeco.javacpp.opencv_video;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;


//############################################################################
public class Flowig_ implements PlugIn {

//############################################################################
// constants
    // color used for bounding boxes
    static final int BOUNDS_COLOR = 0xff_ff_00_ff; // magenta
    // how often should images be downscaled before flow computation
    static final int SCALE_SIZE = 0;
    // saturation scale factor for flow image visualization
    static final int SCALE_COLOR = 1;
    
    static final String ARGUMENT_DIVIDER = ",";
    
    private FlowType flowType = FlowType.DIS;
    private String dataDir;

//############################################################################
// main (for debugging purposes only)
    public static void main(String[] args) {
        Flowig_ flowig = new Flowig_();
        flowig.run(null);
    }
    
//############################################################################
// plugin (run)
    @Override
    public void run(String arg) {
                
        if(!checkArguments(arg)){
            DirectoryChooser dirChooser
                    = new DirectoryChooser("Choose directory with images");
            dataDir = dirChooser.getDirectory();
            if (null == dataDir) {
                return;
            }
        }
        
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<ImagePlus> images = new ArrayList<>();
        File dir = new File(dataDir);
        File[] directoryListing = dir.listFiles();
        if (directoryListing != null) {
            for (File child : directoryListing) {
                if (child.isDirectory()) {
                    continue;
                }
                paths.add(child.getAbsolutePath());
            }
            if (paths.size() < 2) {
                IJ.error("Not enough images: please select a folder with two or more images");
                System.exit(1);
            }
            Collections.sort(paths, String::compareTo);
            for (String s : paths) {
                images.add(new ImagePlus(s));
            }
        } 
        
        Overlay o = getBoundsBasedMovement(images, false);
        ArrayList<ImagePlus> flows = getOpticalFlowBasedMovement(images, false, flowType);
        
        final int w = images.get(0).getWidth();
        final int h = images.get(0).getHeight();
        final Composite comp = BlendComposite.getInstance(BlendComposite.BlendingMode.MULTIPLY);
        
        ImageRoi imgRoi = new ImageRoi(0, 0, flows.get(0).getProcessor());
        imgRoi.setComposite(comp);
        o.add(imgRoi);
        
        ImagePlus vizImg = new ImagePlus("flow", images.get(0).getProcessor());
        vizImg.setImage(images.get(0));
        vizImg.setOverlay(o);
        vizImg.show();
        
        for (int i = 0; i < images.size(); ++i) {
            vizImg.setImage(images.get(i));
            o.remove(imgRoi);
            imgRoi = new ImageRoi(0, 0, flows.get(i).getProcessor());
            imgRoi.setComposite(comp);
            o.add(imgRoi);
            IJ.wait(1000);
        }
    }
    
   
//############################################################################    
//############################################################################    
// bounding box based movement estimation
//############################################################################    
//############################################################################
// ######## draw movement vectors for all images into overlay
    static public Overlay getBoundsBasedMovement(ArrayList<ImagePlus> images, boolean viz) {
        if (images.size() < 2)
            return new Overlay();
        
        ArrayList<Vector> centers = new ArrayList<>();
        ArrayList<Rectangle> bounds = new ArrayList<>();
        
        final int w = images.get(0).getWidth();
        final int h = images.get(0).getHeight();
        
        ImageProcessor vizIp = new ColorProcessor(w, h);
        ImagePlus vizImg = new ImagePlus("movement", vizIp);
        
        Overlay o = new Overlay();
        
        if (viz) {
            vizImg.setOverlay(o);
            vizImg.show();
        }
        
        int i = 0;
        for (ImagePlus img : images) {
            Rectangle bound = getBounds(img, BOUNDS_COLOR);

            if(bound.width == 0 || bound.height == 0) {
                continue;
            }
            
            bounds.add(bound);
                           
            float centerX = bound.x + bound.width / 2f;
            float centerY = bound.y + bound.height / 2f;
           
            centers.add(new Vector(centerX, centerY));
            
            if (i > 0) {
                Vector centerA = centers.get(i-1);
                Vector centerB = centers.get(i);
                Arrow roi = new Arrow(centerA.x, centerA.y,
                                      centerB.x, centerB.y);
                TextRoi idRoi = new TextRoi(centerB.x, centerB.y, ""+i);
                o.add(idRoi);
                System.out.println("" + i + ": ");
                o.add(roi);
                Vector ab = centerB.minus(centerA);
                double length = ab.length();
                System.out.println("Velocity: " + length);
                Vector direction = ab.unit();
                System.out.println("Direction: " + direction);
                System.out.println("--------");
            }  
            i++;
            
            if (viz)
                vizImg.setImage(img);
        }
        
        System.out.println("==========================");
        
        if (viz)
            vizImg.close();
        
        return o;
    }
       
// ######## bbox detection    
    static public Rectangle getBounds(ImagePlus image, int boundsColor) {
        ImageProcessor ip = image.getProcessor();
        final int w = image.getWidth();
        final int h = image.getHeight();
        
        int[][] pix = ip.getIntArray();
        ArrayList<Float> pListX = new ArrayList<>();
        ArrayList<Float> pListY = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (pix[x][y] == boundsColor) {
                    pListX.add((float)x);
                    pListY.add((float)y);
                }
            }
        }
        float[] pX = new float[pListY.size()];
        float[] pY = new float[pListX.size()];
        for (int i = 0; i < pListX.size(); ++i) {
            pX[i] = (float)pListX.get(i);
            pY[i] = (float)pListY.get(i);
        }

        PolygonRoi pointsRoi =
            new PolygonRoi(pX, pY,
                           Roi.POLYGON);
        
        Rectangle bounds = pointsRoi.getBounds();
        return bounds;
    }
    
//############################################################################    
//############################################################################    
// optical flow
//############################################################################    
//############################################################################ 
// ######## compute and vizualize optical flow for all images
    static public ArrayList<ImagePlus> getOpticalFlowBasedMovement(ArrayList<ImagePlus> images, boolean viz, FlowType flowType) {
        ArrayList<ImagePlus> imagesOut = new ArrayList<>();
        
        if (images.size() < 2)
            return imagesOut;
        
        
        
        ImagePlus img0 = images.get(0);
        final int w = img0.getWidth();
        final int h = img0.getHeight();
        
        ImageProcessor vizIp = new ColorProcessor(w, h);
        ImagePlus vizImg = new ImagePlus("flow", vizIp);
        
        if (viz)
            vizImg.show();
        
        for (final ImagePlus img1 : images) {
            Mat mat0 = toMat(img0);
            Mat mat1 = toMat(img1);
            for (int i = 0; i < SCALE_SIZE; ++i) {
                opencv_imgproc.pyrDown(mat0, mat0);
                opencv_imgproc.pyrDown(mat1, mat1);
            }
            Mat flow = makeFlow(mat0, mat1, flowType);
            Mat flowImg = drawOpticalFlow(flow);
            for (int i = 0; i < SCALE_SIZE; ++i) {
                opencv_imgproc.pyrUp(flowImg, flowImg);
            }
            ImagePlus img = toImagePlus(flowImg);
            imagesOut.add(img);
            img0.setImage(img1);
            
            if (viz)
                vizImg.setImage(img);
        }
        
        if (viz)
            vizImg.close();
        
        return imagesOut;
    }

    /**
     * 
     * @return True on correct arguments else false
     */
    private boolean checkArguments(String arg) {
        
        String options = arg != null? arg : Macro.getOptions();
                        
        if(options == null){
            return false;
        }
        
        String [] arguments = options.split(ARGUMENT_DIVIDER);
        
        for (String s : arguments){
            
            String[] values = s.split("=");
            String name = values[0];
            String value = values[1];
            
            switch (name) {
                case "path": 
                    dataDir = value
                            .trim()
                            .replace("~", System.getenv("HOME"));
                    break;
                case "flow": 
                    flowType = FlowType.valueOf(value.trim());
                    break;
            }
        }
        
        IJ.log("dataDir: " + dataDir);
        IJ.log("exists? " +new File(dataDir).exists());
        IJ.log("flowType: " + flowType);
     
        
        return true;
    }
    
// ######## flow type
    static public enum FlowType {
        SparseToDense,
        FarneBack,
        DeepFlow,
        DIS,
        DualTVL1
    }
   
// ######## flow computation
    static public Mat makeFlow(Mat mat0, Mat mat1, FlowType type) {
        opencv_imgproc.cvtColor(mat0, mat0, opencv_imgproc.COLOR_BGR2GRAY);
        opencv_imgproc.equalizeHist(mat0, mat0);
        opencv_imgproc.cvtColor(mat1, mat1, opencv_imgproc.COLOR_BGR2GRAY);
        opencv_imgproc.equalizeHist(mat1, mat1);
        opencv_video.DenseOpticalFlow flow;
        switch (type) {
            default:
            case SparseToDense:
                flow = opencv_optflow.createOptFlow_SparseToDense();
                break;
            case FarneBack:
                flow = opencv_optflow.createOptFlow_Farneback();
                break;
            case DeepFlow:
                flow = opencv_optflow.createOptFlow_DeepFlow();
                break;
            case DIS:
                flow = opencv_optflow.createOptFlow_DIS();
                break;
            case DualTVL1:
                flow = opencv_video.createOptFlow_DualTVL1();
                break;
        }
        Mat matFlow = new Mat();
        flow.calc(mat0, mat1, matFlow);
        return matFlow;
    }
    
// ######## flow vector validation
    static public boolean isFlowCorrect(float ux, float uy) {
        return (   !Float.isNaN(ux)
                && !Float.isNaN(uy)
                && Math.abs(ux) < 1e9
                && Math.abs(uy) < 1e9);
    }

// ######## flow visualization colors (as color wheel)
    static final int RY = 15;
    static final int YG = 6;
    static final int GC = 4;
    static final int CB = 11;
    static final int BM = 13;
    static final int MR = 6;
    static final int NCOLS = RY + YG + GC + CB + BM + MR;
    static final Color[] COLORWHEEL = new Color[NCOLS];
    static {
        int k = 0;
        for (int i = 0; i < RY; ++i, ++k) {
            COLORWHEEL[k] = new Color(255, 255 * i / RY, 0);
        }
        for (int i = 0; i < YG; ++i, ++k) {
            COLORWHEEL[k] = new Color(255 - 255 * i / YG, 255, 0);
        }
        for (int i = 0; i < GC; ++i, ++k) {
            COLORWHEEL[k] = new Color(0, 255, 255 * i / GC);
        }
        for (int i = 0; i < CB; ++i, ++k) {
            COLORWHEEL[k] = new Color(0, 255 - 255 * i / CB, 255);
        }
        for (int i = 0; i < BM; ++i, ++k) {
            COLORWHEEL[k] = new Color(255 * i / BM, 0, 255);
        }
        for (int i = 0; i < MR; ++i, ++k) {
            COLORWHEEL[k] = new Color(255, 0, 255 - 255 * i / MR);
        }
    }
    
// ######## visualize flow vector as color
    static public Color computeColor(final float fx, final float fy) {
        final float rad = (float) (Math.sqrt(fx * fx + fy * fy));
        final float a = (float) (Math.atan2(-fy, -fx) / Math.PI);
        return computeColorRad(rad, a);
    }
    
    static public Color computeColorRad(final float rad, final float a) {
        final float fk = (a + 1.0f) / 2.0f * (NCOLS - 1);
        final int k0 = (int) fk;
        final int k1 = (k0 + 1) % NCOLS;
        final float f = fk - k0;

        Color pix = new Color();

        for (int b = 0; b < 3; b++) {
            final float col0 = COLORWHEEL[k0].get(b) / 255.f;
            final float col1 = COLORWHEEL[k1].get(b) / 255.f;

            float col = (1 - f) * col0 + f * col1;

            if (rad <= 1) {
                col = 1 - rad * (1 - col);  // increase saturation with radius
            } else {
                col *= .75f; // out of range
            }
            pix.set(2 - b, (int) (255.f * col));
        }

        return pix;
    }
    
// ######## visualize entire flow field with colors
    static public Mat drawOpticalFlow(final Mat flow) {
        return drawOpticalFlow(flow, -1);
    }
    static public Mat drawOpticalFlow(final Mat flow, final float maxmotion) {
//        ImageProcessor ip = new ColorProcessor(flow.cols(), flow.rows());
//        ImagePlus imp = new ImagePlus("flow", ip);
        Mat dst = new Mat(flow.size(), opencv_core.CV_8UC3);

        // determine motion range:
        float maxrad = maxmotion;
        FloatIndexer idx = flow.createIndexer();
        if (maxmotion <= 0) {
            maxrad = 1;
            for (int y = 0; y < flow.rows(); ++y) {
                for (int x = 0; x < flow.cols(); ++x) {
                    final float ux = idx.get(y, x * 2);
                    final float uy = idx.get(y, x * 2 + 1);

                    if (!isFlowCorrect(ux, uy)) {
                        continue;
                    }

                    maxrad = (float) Math.max(maxrad, Math.sqrt(ux * ux + uy * uy));
                }
            }
        }

        UByteRawIndexer idxOut = dst.createIndexer(true);
        for (int y = 0; y < flow.rows(); ++y) {
            for (int x = 0; x < flow.cols(); ++x) {
                final float ux = idx.get(y, x * 2);
                final float uy = idx.get(y, x * 2 + 1);

                if (!isFlowCorrect(ux, uy)) {
                    continue;
                }
                final Color c = computeColor(ux / maxrad, uy / maxrad);
//                ip.set(x, y, (c.r * m) << 16 | (c.g * m) << 8 | (c.b * m));
                idxOut.put(y, x * 3, c.r*SCALE_COLOR);
                idxOut.put(y, x * 3 + 1, c.g*SCALE_COLOR);
                idxOut.put(y, x * 3 + 2, c.b*SCALE_COLOR);
            }
        }
        return dst;
    }

//############################################################################    
//############################################################################
// Vector
//############################################################################
//############################################################################
    static public class Vector {
        double x = 0;
        double y = 0;
        
        public Vector(final double x,final double y) {
            this.x = x;
            this.y = y;
        }
        
       public Vector minus(final Vector other) {
            final double x = this.x - other.x;
            final double y = this.y - other.y;
            return new Vector(x, y);
       }
       public Vector divide(final double d) {
            return new Vector(x/d, y/d);
        }
       public double length() {
           return Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2));
       }
       public Vector unit() {
            return divide(length());
       }
       @Override
       public String toString() {
            return "(" + x + "," + y + ")";
       }
       
    }
    
//############################################################################
//############################################################################    
// Color
//############################################################################
//############################################################################
    static public class Color {

        public int r = 0;
        public int g = 0;
        public int b = 0;

        public Color(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        public Color() {
            this(0, 0, 0);
        }

        public void set(int i, int c) {
            switch (i) {
                case 0:
                    r = c;
                    break;
                case 1:
                    g = c;
                    break;
                case 2:
                    b = c;
                    break;
                default:
                    break;
            }
        }

        public int get(int i) {
            switch (i) {
                case 0:
                    return r;
                case 1:
                    return g;
                case 2:
                    return b;
                default:
                    return 0;
            }
        }
    }
    
//############################################################################
//############################################################################
// opencv <-> imagej
//############################################################################
//############################################################################
    static public ImagePlus toImagePlus(final Mat mat, final String title) {
        Java2DFrameConverter javaConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        final Frame frame = matConverter.convert(mat);
        final BufferedImage buffImage = javaConverter.convert(frame);
        return new ImagePlus(title, buffImage);
    }
    
    static public ImagePlus toImagePlus(final Mat mat) {
        return toImagePlus(mat, "");
    }
    
    static public Mat toMat(final ImagePlus imagePlus) {
        Java2DFrameConverter javaConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        final BufferedImage image = imagePlus.getBufferedImage();        
        final Frame frame = javaConverter.convert(image);
        return matConverter.convert(frame);
    }

//############################################################################
    
//############################################################################
//############################################################################
//############################################################################
//############################################################################
//############################################################################

    
/*
 * $Id: BlendComposite.java,v 1.9 2007/02/28 01:21:29 gfx Exp $
 *
 * Dual-licensed under LGPL (Sun and Romain Guy) and BSD (Romain Guy).
 *
 * Copyright 2005 Sun Microsystems, Inc., 4150 Network Circle,
 * Santa Clara, California 95054, U.S.A. All rights reserved.
 *
 * Copyright (c) 2006 Romain Guy <romain.guy@mac.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */



/**
 * <p>A blend composite defines the rule according to which a drawing primitive
 * (known as the source) is mixed with existing graphics (know as the
 * destination.)</p>
 * <p><code>BlendComposite</code> is an implementation of the
 * {@link java.awt.Composite} interface and must therefore be set as a state on
 * a {@link java.awt.Graphics2D} surface.</p>
 * <p>Please refer to {@link java.awt.Graphics2D#setComposite(java.awt.Composite)}
 * for more information on how to use this class with a graphics surface.</p>
 * <h2>Blending Modes</h2>
 * <p>This class offers a certain number of blending modes, or compositing
 * rules. These rules are inspired from graphics editing software packages,
 * like <em>Adobe Photoshop</em> or <em>The GIMP</em>.</p>
 * <p>Given the wide variety of implemented blending modes and the difficulty
 * to describe them with words, please refer to those tools to visually see
 * the result of these blending modes.</p>
 * <h2>Opacity</h2>
 * <p>Each blending mode has an associated opacity, defined as a float value
 * between 0.0 and 1.0. Changing the opacity controls the force with which the
 * compositing operation is applied. For instance, a composite with an opacity
 * of 0.0 will not draw the source onto the destination. With an opacity of
 * 1.0, the source will be fully drawn onto the destination, according to the
 * selected blending mode rule.</p>
 * <p>The opacity, or alpha value, is used by the composite instance to mutiply
 * the alpha value of each pixel of the source when being composited over the
 * destination.</p>
 * <h2>Creating a Blend Composite</h2>
 * <p>Blend composites can be created in various manners:</p>
 * <ul>
 *   <li>Use one of the pre-defined instance. Example:
 *     <code>BlendComposite.Average</code>.</li>
 *   <li>Derive one of the pre-defined instances by calling
 *     {@link #derive(float)} or {@link #derive(BlendingMode)}. Deriving allows
 *     you to change either the opacity or the blending mode. Example:
 *     <code>BlendComposite.Average.derive(0.5f)</code>.</li>
 *   <li>Use a factory method: {@link #getInstance(BlendingMode)} or
 *     {@link #getInstance(BlendingMode, float)}.</li>
 * </ul>
 * <h2>Implementation Caveat</h2>
 * <p>TThe blending mode <em>SoftLight</em> has not been implemented yet.</p>
 *
 * @see java.awt.Graphics2D
 * @see java.awt.Composite
 * @see java.awt.AlphaComposite
 * @author Romain Guy <romain.guy@mac.com>
 */
 static final class BlendComposite implements Composite {
    /**
     * <p>A blending mode defines the compositing rule of a
     * {@link BlendComposite}.</p>
     *
     * @author Romain Guy <romain.guy@mac.com>
     */
    public enum BlendingMode {
        AVERAGE,
        MULTIPLY,
        SCREEN,
        DARKEN,
        LIGHTEN,
        OVERLAY,
        HARD_LIGHT,
        SOFT_LIGHT,
        DIFFERENCE,
        NEGATION,
        EXCLUSION,
        COLOR_DODGE,
        INVERSE_COLOR_DODGE,
        SOFT_DODGE,
        COLOR_BURN,
        INVERSE_COLOR_BURN,
        SOFT_BURN,
        REFLECT,
        GLOW,
        FREEZE,
        HEAT,
        ADD,
        SUBTRACT,
        STAMP,
        RED,
        GREEN,
        BLUE,
        HUE,
        SATURATION,
        COLOR,
        LUMINOSITY
    }

    public static final BlendComposite Average = new BlendComposite(BlendingMode.AVERAGE);
    public static final BlendComposite Multiply = new BlendComposite(BlendingMode.MULTIPLY);
    public static final BlendComposite Screen = new BlendComposite(BlendingMode.SCREEN);
    public static final BlendComposite Darken = new BlendComposite(BlendingMode.DARKEN);
    public static final BlendComposite Lighten = new BlendComposite(BlendingMode.LIGHTEN);
    public static final BlendComposite Overlay = new BlendComposite(BlendingMode.OVERLAY);
    public static final BlendComposite HardLight = new BlendComposite(BlendingMode.HARD_LIGHT);
    public static final BlendComposite SoftLight = new BlendComposite(BlendingMode.SOFT_LIGHT);
    public static final BlendComposite Difference = new BlendComposite(BlendingMode.DIFFERENCE);
    public static final BlendComposite Negation = new BlendComposite(BlendingMode.NEGATION);
    public static final BlendComposite Exclusion = new BlendComposite(BlendingMode.EXCLUSION);
    public static final BlendComposite ColorDodge = new BlendComposite(BlendingMode.COLOR_DODGE);
    public static final BlendComposite InverseColorDodge = new BlendComposite(BlendingMode.INVERSE_COLOR_DODGE);
    public static final BlendComposite SoftDodge = new BlendComposite(BlendingMode.SOFT_DODGE);
    public static final BlendComposite ColorBurn = new BlendComposite(BlendingMode.COLOR_BURN);
    public static final BlendComposite InverseColorBurn = new BlendComposite(BlendingMode.INVERSE_COLOR_BURN);
    public static final BlendComposite SoftBurn = new BlendComposite(BlendingMode.SOFT_BURN);
    public static final BlendComposite Reflect = new BlendComposite(BlendingMode.REFLECT);
    public static final BlendComposite Glow = new BlendComposite(BlendingMode.GLOW);
    public static final BlendComposite Freeze = new BlendComposite(BlendingMode.FREEZE);
    public static final BlendComposite Heat = new BlendComposite(BlendingMode.HEAT);
    public static final BlendComposite Add = new BlendComposite(BlendingMode.ADD);
    public static final BlendComposite Subtract = new BlendComposite(BlendingMode.SUBTRACT);
    public static final BlendComposite Stamp = new BlendComposite(BlendingMode.STAMP);
    public static final BlendComposite Red = new BlendComposite(BlendingMode.RED);
    public static final BlendComposite Green = new BlendComposite(BlendingMode.GREEN);
    public static final BlendComposite Blue = new BlendComposite(BlendingMode.BLUE);
    public static final BlendComposite Hue = new BlendComposite(BlendingMode.HUE);
    public static final BlendComposite Saturation = new BlendComposite(BlendingMode.SATURATION);
    public static final BlendComposite Color = new BlendComposite(BlendingMode.COLOR);
    public static final BlendComposite Luminosity = new BlendComposite(BlendingMode.LUMINOSITY);

    private final float alpha;
    private final BlendingMode mode;

    private BlendComposite(BlendingMode mode) {
        this(mode, 1.0f);
    }

    private BlendComposite(BlendingMode mode, float alpha) {
        this.mode = mode;

        if (alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException(
                    "alpha must be comprised between 0.0f and 1.0f");
        }
        this.alpha = alpha;
    }

    /**
     * <p>Creates a new composite based on the blending mode passed
     * as a parameter. A default opacity of 1.0 is applied.</p>
     *
     * @param mode the blending mode defining the compositing rule
     * @return a new <code>BlendComposite</code> based on the selected blending
     *   mode, with an opacity of 1.0
     */
    public static BlendComposite getInstance(BlendingMode mode) {
        return new BlendComposite(mode);
    }

    /**
     * <p>Creates a new composite based on the blending mode and opacity passed
     * as parameters. The opacity must be a value between 0.0 and 1.0.</p>
     *
     * @param mode the blending mode defining the compositing rule
     * @param alpha the constant alpha to be multiplied with the alpha of the
     *   source. <code>alpha</code> must be a floating point between 0.0 and 1.0.
     * @throws IllegalArgumentException if the opacity is less than 0.0 or
     *   greater than 1.0
     * @return a new <code>BlendComposite</code> based on the selected blending
     *   mode and opacity
     */
    public static BlendComposite getInstance(BlendingMode mode, float alpha) {
        return new BlendComposite(mode, alpha);
    }

    /**
     * <p>Returns a <code>BlendComposite</code> object that uses the specified
     * blending mode and this object's alpha value. If the newly specified
     * blending mode is the same as this object's, this object is returned.</p>
     *
     * @param mode the blending mode defining the compositing rule
     * @return a <code>BlendComposite</code> object derived from this object,
     *   that uses the specified blending mode
     */
    public BlendComposite derive(BlendingMode mode) {
        return this.mode == mode ? this : new BlendComposite(mode, getAlpha());
    }

    /**
     * <p>Returns a <code>BlendComposite</code> object that uses the specified
     * opacity, or alpha, and this object's blending mode. If the newly specified
     * opacity is the same as this object's, this object is returned.</p>
     *
     * @param alpha the constant alpha to be multiplied with the alpha of the
     *   source. <code>alpha</code> must be a floating point between 0.0 and 1.0.
     * @throws IllegalArgumentException if the opacity is less than 0.0 or
     *   greater than 1.0
     * @return a <code>BlendComposite</code> object derived from this object,
     *   that uses the specified blending mode
     */
    public BlendComposite derive(float alpha) {
        return this.alpha == alpha ? this : new BlendComposite(getMode(), alpha);
    }

    /**
     * <p>Returns the opacity of this composite. If no opacity has been defined,
     * 1.0 is returned.</p>
     *
     * @return the alpha value, or opacity, of this object
     */
    public float getAlpha() {
        return alpha;
    }

    /**
     * <p>Returns the blending mode of this composite.</p>
     *
     * @return the blending mode used by this object
     */
    public BlendingMode getMode() {
        return mode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Float.floatToIntBits(alpha) * 31 + mode.ordinal();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BlendComposite)) {
            return false;
        }

        BlendComposite bc = (BlendComposite) obj;
        return mode == bc.mode && alpha == bc.alpha;
    }

    private static boolean checkComponentsOrder(ColorModel cm) {
        if (cm instanceof DirectColorModel &&
                cm.getTransferType() == DataBuffer.TYPE_INT) {
            DirectColorModel directCM = (DirectColorModel) cm;
            
            return directCM.getRedMask() == 0x00FF0000 &&
                   directCM.getGreenMask() == 0x0000FF00 &&
                   directCM.getBlueMask() == 0x000000FF &&
                   (directCM.getNumComponents() != 4 ||
                    directCM.getAlphaMask() == 0xFF000000);
        }
        
        return false;
    }
    
    /**
     * {@inheritDoc}
     */
    public CompositeContext createContext(ColorModel srcColorModel,
                                          ColorModel dstColorModel,
                                          RenderingHints hints) {
        if (!checkComponentsOrder(srcColorModel) ||
                !checkComponentsOrder(dstColorModel)) {
            throw new RasterFormatException("Incompatible color models");
        }
        
        return new BlendingContext(this);
    }

    private static final class BlendingContext implements CompositeContext {
        private final Blender blender;
        private final BlendComposite composite;

        private BlendingContext(BlendComposite composite) {
            this.composite = composite;
            this.blender = Blender.getBlenderFor(composite);
        }

        public void dispose() {
        }

        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            int width = Math.min(src.getWidth(), dstIn.getWidth());
            int height = Math.min(src.getHeight(), dstIn.getHeight());

            float alpha = composite.getAlpha();

            int[] result = new int[4];
            int[] srcPixel = new int[4];
            int[] dstPixel = new int[4];
            int[] srcPixels = new int[width];
            int[] dstPixels = new int[width];

            for (int y = 0; y < height; y++) {
                src.getDataElements(0, y, width, 1, srcPixels);
                dstIn.getDataElements(0, y, width, 1, dstPixels);
                for (int x = 0; x < width; x++) {
                    // pixels are stored as INT_ARGB
                    // our arrays are [R, G, B, A]
                    int pixel = srcPixels[x];
                    srcPixel[0] = (pixel >> 16) & 0xFF;
                    srcPixel[1] = (pixel >>  8) & 0xFF;
                    srcPixel[2] = (pixel      ) & 0xFF;
                    srcPixel[3] = (pixel >> 24) & 0xFF;

                    pixel = dstPixels[x];
                    dstPixel[0] = (pixel >> 16) & 0xFF;
                    dstPixel[1] = (pixel >>  8) & 0xFF;
                    dstPixel[2] = (pixel      ) & 0xFF;
                    dstPixel[3] = (pixel >> 24) & 0xFF;

                    blender.blend(srcPixel, dstPixel, result);

                    // mixes the result with the opacity
                    dstPixels[x] = ((int) (dstPixel[3] + (result[3] - dstPixel[3]) * alpha) & 0xFF) << 24 |
                                   ((int) (dstPixel[0] + (result[0] - dstPixel[0]) * alpha) & 0xFF) << 16 |
                                   ((int) (dstPixel[1] + (result[1] - dstPixel[1]) * alpha) & 0xFF) <<  8 |
                                    (int) (dstPixel[2] + (result[2] - dstPixel[2]) * alpha) & 0xFF;
                }
                dstOut.setDataElements(0, y, width, 1, dstPixels);
            }
        }
    }

    private static abstract class Blender {
        public abstract void blend(int[] src, int[] dst, int[] result);

        public static Blender getBlenderFor(BlendComposite composite) {
            switch (composite.getMode()) {
                case ADD:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = Math.min(255, src[0] + dst[0]);
                            result[1] = Math.min(255, src[1] + dst[1]);
                            result[2] = Math.min(255, src[2] + dst[2]);
                            result[3] = Math.min(255, src[3] + dst[3]);
                        }
                    };
                case AVERAGE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = (src[0] + dst[0]) >> 1;
                            result[1] = (src[1] + dst[1]) >> 1;
                            result[2] = (src[2] + dst[2]) >> 1;
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case BLUE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0];
                            result[1] = src[1];
                            result[2] = dst[2];
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case COLOR:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            ColorUtilities.RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            ColorUtilities.RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            ColorUtilities.HSLtoRGB(srcHSL[0], srcHSL[1], dstHSL[2], result);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case COLOR_BURN:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = src[0] == 0 ? 0 :
                                Math.max(0, 255 - (((255 - dst[0]) << 8) / src[0]));
                            result[1] = src[1] == 0 ? 0 :
                                Math.max(0, 255 - (((255 - dst[1]) << 8) / src[1]));
                            result[2] = src[2] == 0 ? 0 :
                                Math.max(0, 255 - (((255 - dst[2]) << 8) / src[2]));
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case COLOR_DODGE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = src[0] == 255 ? 255 :
                                Math.min((dst[0] << 8) / (255 - src[0]), 255);
                            result[1] = src[1] == 255 ? 255 :
                                Math.min((dst[1] << 8) / (255 - src[1]), 255);
                            result[2] = src[2] == 255 ? 255 :
                                Math.min((dst[2] << 8) / (255 - src[2]), 255);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case DARKEN:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = Math.min(src[0], dst[0]);
                            result[1] = Math.min(src[1], dst[1]);
                            result[2] = Math.min(src[2], dst[2]);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case DIFFERENCE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = Math.abs(dst[0] - src[0]);
                            result[1] = Math.abs(dst[1] - src[1]);
                            result[2] = Math.abs(dst[2] - src[2]);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case EXCLUSION:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] + src[0] - (dst[0] * src[0] >> 7);
                            result[1] = dst[1] + src[1] - (dst[1] * src[1] >> 7);
                            result[2] = dst[2] + src[2] - (dst[2] * src[2] >> 7);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case FREEZE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = src[0] == 0 ? 0 :
                                Math.max(0, 255 - (255 - dst[0]) * (255 - dst[0]) / src[0]);
                            result[1] = src[1] == 0 ? 0 :
                                Math.max(0, 255 - (255 - dst[1]) * (255 - dst[1]) / src[1]);
                            result[2] = src[2] == 0 ? 0 :
                                Math.max(0, 255 - (255 - dst[2]) * (255 - dst[2]) / src[2]);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case GLOW:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] == 255 ? 255 :
                                Math.min(255, src[0] * src[0] / (255 - dst[0]));
                            result[1] = dst[1] == 255 ? 255 :
                                Math.min(255, src[1] * src[1] / (255 - dst[1]));
                            result[2] = dst[2] == 255 ? 255 :
                                Math.min(255, src[2] * src[2] / (255 - dst[2]));
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case GREEN:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0];
                            result[1] = dst[1];
                            result[2] = src[2];
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case HARD_LIGHT:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = src[0] < 128 ? dst[0] * src[0] >> 7 :
                                255 - ((255 - src[0]) * (255 - dst[0]) >> 7);
                            result[1] = src[1] < 128 ? dst[1] * src[1] >> 7 :
                                255 - ((255 - src[1]) * (255 - dst[1]) >> 7);
                            result[2] = src[2] < 128 ? dst[2] * src[2] >> 7 :
                                255 - ((255 - src[2]) * (255 - dst[2]) >> 7);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case HEAT:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] == 0 ? 0 :
                                Math.max(0, 255 - (255 - src[0]) * (255 - src[0]) / dst[0]);
                            result[1] = dst[1] == 0 ? 0 :
                                Math.max(0, 255 - (255 - src[1]) * (255 - src[1]) / dst[1]);
                            result[2] = dst[2] == 0 ? 0 :
                                Math.max(0, 255 - (255 - src[2]) * (255 - src[2]) / dst[2]);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case HUE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            ColorUtilities.RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            ColorUtilities.RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            ColorUtilities.HSLtoRGB(srcHSL[0], dstHSL[1], dstHSL[2], result);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case INVERSE_COLOR_BURN:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] == 0 ? 0 :
                                Math.max(0, 255 - (((255 - src[0]) << 8) / dst[0]));
                            result[1] = dst[1] == 0 ? 0 :
                                Math.max(0, 255 - (((255 - src[1]) << 8) / dst[1]));
                            result[2] = dst[2] == 0 ? 0 :
                                Math.max(0, 255 - (((255 - src[2]) << 8) / dst[2]));
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case INVERSE_COLOR_DODGE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] == 255 ? 255 :
                                Math.min((src[0] << 8) / (255 - dst[0]), 255);
                            result[1] = dst[1] == 255 ? 255 :
                                Math.min((src[1] << 8) / (255 - dst[1]), 255);
                            result[2] = dst[2] == 255 ? 255 :
                                Math.min((src[2] << 8) / (255 - dst[2]), 255);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case LIGHTEN:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = Math.max(src[0], dst[0]);
                            result[1] = Math.max(src[1], dst[1]);
                            result[2] = Math.max(src[2], dst[2]);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case LUMINOSITY:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            ColorUtilities.RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            ColorUtilities.RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            ColorUtilities.HSLtoRGB(dstHSL[0], dstHSL[1], srcHSL[2], result);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case MULTIPLY:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = (src[0] * dst[0]) >> 8;
                            result[1] = (src[1] * dst[1]) >> 8;
                            result[2] = (src[2] * dst[2]) >> 8;
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case NEGATION:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = 255 - Math.abs(255 - dst[0] - src[0]);
                            result[1] = 255 - Math.abs(255 - dst[1] - src[1]);
                            result[2] = 255 - Math.abs(255 - dst[2] - src[2]);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case OVERLAY:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] < 128 ? dst[0] * src[0] >> 7 :
                                255 - ((255 - dst[0]) * (255 - src[0]) >> 7);
                            result[1] = dst[1] < 128 ? dst[1] * src[1] >> 7 :
                                255 - ((255 - dst[1]) * (255 - src[1]) >> 7);
                            result[2] = dst[2] < 128 ? dst[2] * src[2] >> 7 :
                                255 - ((255 - dst[2]) * (255 - src[2]) >> 7);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case RED:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = src[0];
                            result[1] = dst[1];
                            result[2] = dst[2];
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case REFLECT:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = src[0] == 255 ? 255 :
                                Math.min(255, dst[0] * dst[0] / (255 - src[0]));
                            result[1] = src[1] == 255 ? 255 :
                                Math.min(255, dst[1] * dst[1] / (255 - src[1]));
                            result[2] = src[2] == 255 ? 255 :
                                Math.min(255, dst[2] * dst[2] / (255 - src[2]));
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case SATURATION:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            ColorUtilities.RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            ColorUtilities.RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            ColorUtilities.HSLtoRGB(dstHSL[0], srcHSL[1], dstHSL[2], result);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case SCREEN:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = 255 - ((255 - src[0]) * (255 - dst[0]) >> 8);
                            result[1] = 255 - ((255 - src[1]) * (255 - dst[1]) >> 8);
                            result[2] = 255 - ((255 - src[2]) * (255 - dst[2]) >> 8);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case SOFT_BURN:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] + src[0] < 256 ?
                                (dst[0] == 255 ? 255 :
                                 Math.min(255, (src[0] << 7) / (255 - dst[0]))) :
                                                                                Math.max(0, 255 - (((255 - dst[0]) << 7) / src[0]));
                            result[1] = dst[1] + src[1] < 256 ?
                                (dst[1] == 255 ? 255 :
                                 Math.min(255, (src[1] << 7) / (255 - dst[1]))) :
                                                                                Math.max(0, 255 - (((255 - dst[1]) << 7) / src[1]));
                            result[2] = dst[2] + src[2] < 256 ?
                                (dst[2] == 255 ? 255 :
                                 Math.min(255, (src[2] << 7) / (255 - dst[2]))) :
                                                                                Math.max(0, 255 - (((255 - dst[2]) << 7) / src[2]));
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case SOFT_DODGE:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0] + src[0] < 256 ?
                                (src[0] == 255 ? 255 :
                                 Math.min(255, (dst[0] << 7) / (255 - src[0]))) :
                                    Math.max(0, 255 - (((255 - src[0]) << 7) / dst[0]));
                            result[1] = dst[1] + src[1] < 256 ?
                                (src[1] == 255 ? 255 :
                                 Math.min(255, (dst[1] << 7) / (255 - src[1]))) :
                                    Math.max(0, 255 - (((255 - src[1]) << 7) / dst[1]));
                            result[2] = dst[2] + src[2] < 256 ?
                                (src[2] == 255 ? 255 :
                                 Math.min(255, (dst[2] << 7) / (255 - src[2]))) :
                                    Math.max(0, 255 - (((255 - src[2]) << 7) / dst[2]));
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case SOFT_LIGHT:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            int mRed = src[0] * dst[0] / 255;
                            int mGreen = src[1] * dst[1] / 255;
                            int mBlue = src[2] * dst[2] / 255;
                            result[0] = mRed + src[0] * (255 - ((255 - src[0]) * (255 - dst[0]) / 255) - mRed) / 255;
                            result[1] = mGreen + src[1] * (255 - ((255 - src[1]) * (255 - dst[1]) / 255) - mGreen) / 255;
                            result[2] = mBlue + src[2] * (255 - ((255 - src[2]) * (255 - dst[2]) / 255) - mBlue) / 255;
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case STAMP:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = Math.max(0, Math.min(255, dst[0] + 2 * src[0] - 256));
                            result[1] = Math.max(0, Math.min(255, dst[1] + 2 * src[1] - 256));
                            result[2] = Math.max(0, Math.min(255, dst[2] + 2 * src[2] - 256));
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
                case SUBTRACT:
                    return new Blender() {
                        @Override
                        public void blend(int[] src, int[] dst, int[] result) {
                            result[0] = Math.max(0, src[0] + dst[0] - 256);
                            result[1] = Math.max(0, src[1] + dst[1] - 256);
                            result[2] = Math.max(0, src[2] + dst[2] - 256);
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                        }
                    };
            }
            throw new IllegalArgumentException("Blender not implemented for " +
                                               composite.getMode().name());
        }
    }
}


 /*
  * $Id: ColorUtilities.java,v 1.1 2006/12/15 13:53:13 gfx Exp $
  *
  * Dual-licensed under LGPL (Sun and Romain Guy) and BSD (Romain Guy).
  *
  * Copyright 2006 Sun Microsystems, Inc., 4150 Network Circle,
  * Santa Clara, California 95054, U.S.A. All rights reserved.
  *
  * Copyright (c) 2006 Romain Guy <romain.guy@mac.com>
  * All rights reserved.
  *
  * Redistribution and use in source and binary forms, with or without
  * modification, are permitted provided that the following conditions
  * are met:
  * 1. Redistributions of source code must retain the above copyright
  *    notice, this list of conditions and the following disclaimer.
  * 2. Redistributions in binary form must reproduce the above copyright
  *    notice, this list of conditions and the following disclaimer in the
  *    documentation and/or other materials provided with the distribution.
  * 3. The name of the author may not be used to endorse or promote products
  *    derived from this software without specific prior written permission.
  *
  * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
  * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
  * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
  * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
  * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
  * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
  * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
  * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
  * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  */



 /**
  * <p><code>ColorUtilities</code> contains a set of tools to perform
  * common color operations easily.</p>
  *
  * @author Romain Guy <romain.guy@mac.com>
  */
  static class ColorUtilities {
     private ColorUtilities() {
     }

     /**
      * <p>Returns the HSL (Hue/Saturation/Luminance) equivalent of a given
      * RGB color. All three HSL components are between 0.0 and 1.0.</p>
      *
      * @param color the RGB color to convert
      * @return a new array of 3 floats corresponding to the HSL components
      */
     public static float[] RGBtoHSL(java.awt.Color color) {
         return RGBtoHSL(color.getRed(), color.getGreen(), color.getBlue(), null);
     }

     /**
      * <p>Returns the HSL (Hue/Saturation/Luminance) equivalent of a given
      * RGB color. All three HSL components are between 0.0 and 1.0.</p>
      *
      * @param color the RGB color to convert
      * @param hsl a pre-allocated array of floats; can be null
      * @return <code>hsl</code> if non-null, a new array of 3 floats otherwise
      * @throws IllegalArgumentException if <code>hsl</code> has a length lower
      *   than 3
      */
     public static float[] RGBtoHSL(java.awt.Color color, float[] hsl) {
         return RGBtoHSL(color.getRed(), color.getGreen(), color.getBlue(), hsl);
     }

     /**
      * <p>Returns the HSL (Hue/Saturation/Luminance) equivalent of a given
      * RGB color. All three HSL components are between 0.0 and 1.0.</p>
      *
      * @param r the red component, between 0 and 255
      * @param g the green component, between 0 and 255
      * @param b the blue component, between 0 and 255
      * @return a new array of 3 floats corresponding to the HSL components
      */
     public static float[] RGBtoHSL(int r, int g, int b) {
         return RGBtoHSL(r, g, b, null);
     }

     /**
      * <p>Returns the HSL (Hue/Saturation/Luminance) equivalent of a given
      * RGB color. All three HSL components are floats between 0.0 and 1.0.</p>
      *
      * @param r the red component, between 0 and 255
      * @param g the green component, between 0 and 255
      * @param b the blue component, between 0 and 255
      * @param hsl a pre-allocated array of floats; can be null
      * @return <code>hsl</code> if non-null, a new array of 3 floats otherwise
      * @throws IllegalArgumentException if <code>hsl</code> has a length lower
      *   than 3
      */
     public static float[] RGBtoHSL(int r, int g, int b, float[] hsl) {
         if (hsl == null) {
             hsl = new float[3];
         } else if (hsl.length < 3) {
             throw new IllegalArgumentException("hsl array must have a length of" +
                                                " at least 3");
         }

         if (r < 0) r = 0;
         else if (r > 255) r = 255;
         if (g < 0) g = 0;
         else if (g > 255) g = 255;
         if (b < 0) b = 0;
         else if (b > 255) b = 255;

         float var_R = (r / 255f);
         float var_G = (g / 255f);
         float var_B = (b / 255f);

         float var_Min;
         float var_Max;
         float del_Max;

         if (var_R > var_G) {
             var_Min = var_G;
             var_Max = var_R;
         } else {
             var_Min = var_R;
             var_Max = var_G;
         }
         if (var_B > var_Max) {
             var_Max = var_B;
         }
         if (var_B < var_Min) {
             var_Min = var_B;
         }

         del_Max = var_Max - var_Min;

         float H, S, L;
         L = (var_Max + var_Min) / 2f;

         if (del_Max - 0.01f <= 0.0f) {
             H = 0;
             S = 0;
         } else {
             if (L < 0.5f) {
                 S = del_Max / (var_Max + var_Min);
             } else {
                 S = del_Max / (2 - var_Max - var_Min);
             }

             float del_R = (((var_Max - var_R) / 6f) + (del_Max / 2f)) / del_Max;
             float del_G = (((var_Max - var_G) / 6f) + (del_Max / 2f)) / del_Max;
             float del_B = (((var_Max - var_B) / 6f) + (del_Max / 2f)) / del_Max;

             if (var_R == var_Max) {
                 H = del_B - del_G;
             } else if (var_G == var_Max) {
                 H = (1 / 3f) + del_R - del_B;
             } else {
                 H = (2 / 3f) + del_G - del_R;
             }
             if (H < 0) {
                 H += 1;
             }
             if (H > 1) {
                 H -= 1;
             }
         }

         hsl[0] = H;
         hsl[1] = S;
         hsl[2] = L;

         return hsl;
     }

     /**
      * <p>Returns the RGB equivalent of a given HSL (Hue/Saturation/Luminance)
      * color.</p>
      *
      * @param h the hue component, between 0.0 and 1.0
      * @param s the saturation component, between 0.0 and 1.0
      * @param l the luminance component, between 0.0 and 1.0
      * @return a new <code>Color</code> object equivalent to the HSL components
      */
     public static java.awt.Color HSLtoRGB(float h, float s, float l) {
         int[] rgb = HSLtoRGB(h, s, l, null);
         return new java.awt.Color(rgb[0], rgb[1], rgb[2]);
     }

     /**
      * <p>Returns the RGB equivalent of a given HSL (Hue/Saturation/Luminance)
      * color. All three RGB components are integers between 0 and 255.</p>
      *
      * @param h the hue component, between 0.0 and 1.0
      * @param s the saturation component, between 0.0 and 1.0
      * @param l the luminance component, between 0.0 and 1.0
      * @param rgb a pre-allocated array of ints; can be null
      * @return <code>rgb</code> if non-null, a new array of 3 ints otherwise
      * @throws IllegalArgumentException if <code>rgb</code> has a length lower
      *   than 3
      */
     public static int[] HSLtoRGB(float h, float s, float l, int[] rgb) {
         if (rgb == null) {
             rgb = new int[3];
         } else if (rgb.length < 3) {
             throw new IllegalArgumentException("rgb array must have a length of" +
                                                " at least 3");
         }

         if (h < 0) h = 0.0f;
         else if (h > 1.0f) h = 1.0f;
         if (s < 0) s = 0.0f;
         else if (s > 1.0f) s = 1.0f;
         if (l < 0) l = 0.0f;
         else if (l > 1.0f) l = 1.0f;

         int R, G, B;

         if (s - 0.01f <= 0.0f) {
             R = (int) (l * 255.0f);
             G = (int) (l * 255.0f);
             B = (int) (l * 255.0f);
         } else {
             float var_1, var_2;
             if (l < 0.5f) {
                 var_2 = l * (1 + s);
             } else {
                 var_2 = (l + s) - (s * l);
             }
             var_1 = 2 * l - var_2;

             R = (int) (255.0f * hue2RGB(var_1, var_2, h + (1.0f / 3.0f)));
             G = (int) (255.0f * hue2RGB(var_1, var_2, h));
             B = (int) (255.0f * hue2RGB(var_1, var_2, h - (1.0f / 3.0f)));
         }

         rgb[0] = R;
         rgb[1] = G;
         rgb[2] = B;

         return rgb;
     }

     private static float hue2RGB(float v1, float v2, float vH) {
         if (vH < 0.0f) {
             vH += 1.0f;
         }
         if (vH > 1.0f) {
             vH -= 1.0f;
         }
         if ((6.0f * vH) < 1.0f) {
             return (v1 + (v2 - v1) * 6.0f * vH);
         }
         if ((2.0f * vH) < 1.0f) {
             return (v2);
         }
         if ((3.0f * vH) < 2.0f) {
             return (v1 + (v2 - v1) * ((2.0f / 3.0f) - vH) * 6.0f);
         }
         return (v1);
     }
 }

    
}


