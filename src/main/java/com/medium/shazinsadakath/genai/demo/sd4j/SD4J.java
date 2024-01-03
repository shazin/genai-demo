/*
 * Copyright (c) 2023 Oracle and/or its affiliates.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.medium.shazinsadakath.genai.demo.sd4j;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A stable diffusion pipeline using ONNX Runtime.
 */
public final class SD4J implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(SD4J.class.getName());

    private final TextEmbedder embedder;
    private final UNet unet;
    private final VAEDecoder vae;
    private final SafetyChecker safety;

    /**
     * Constructs a stable diffusion pipeline from the supplied models.
     * @param embedder The text embedding model. Usually a CLIP variant.
     * @param unet The UNet model which performs the inverse diffusion.
     * @param vae The VAE model which translates from latent space to pixel space.
     * @param safety The safety checker model which checks that the generated image is SFW.
     */
    public SD4J(TextEmbedder embedder, UNet unet, VAEDecoder vae, SafetyChecker safety) {
        this.embedder = embedder;
        this.unet = unet;
        this.vae = vae;
        this.safety = safety;
    }

    /**
     * Saves the buffered image to the supplied path as a png.
     * @param image The image.
     * @param filename The filename to save to.
     * @throws IOException If the file save failed.
     */
    public static void save(BufferedImage image, String filename) throws IOException {
        File f = new File(filename);
        save(image, f);
    }

    /**
     * Saves the buffered image to the supplied path as a png.
     * @param image The image.
     * @param file The file to save to.
     * @throws IOException If the file save failed.
     */
    public static void save(BufferedImage image, File file) throws IOException {
        ImageIO.write(image, "png", file);
    }

    /**
     * Generates a batch of images from the supplied prompts and parameters.
     * <p>
     * Defaults to the LMS scheduler.
     * @param numInferenceSteps The number of diffusion inference steps to take (commonly 20-50 for LMS and Euler Ancestral).
     * @param text The text prompt.
     * @param negativeText The negative text prompt which the image should not contain.
     * @param guidanceScale The strength of the classifier-free guidance (i.e., how much should the image represent the text prompt).
     * @param batchSize The number of images to generate.
     * @param size The image size.
     * @param seed The RNG seed, fixing the seed should produce identical images.
     * @return A list of generated images.
     */
    public List<SDImage> generateImage(int numInferenceSteps, String text, String negativeText, float guidanceScale, int batchSize, ImageSize size, int seed) {
        return generateImage(numInferenceSteps, text, negativeText, guidanceScale, batchSize, size, seed, Schedulers.LMS, (Integer a) -> {});
    }

    /**
     * Generates a batch of images from the supplied generation request.
     * @param request The image generation request.
     * @return A list of generated images.
     */
    public List<SDImage> generateImage(Request request) {
        return generateImage(request, (Integer a) -> {});
    }

    /**
     * Generates a batch of images from the supplied generation request.
     * @param request The image generation request.
     * @param progressCallback A supplier which can be used to update a GUI. It is called after each diffusion step with the current step count.
     * @return A list of generated images.
     */
    public List<SDImage> generateImage(Request request, Consumer<Integer> progressCallback) {
        return generateImage(request.steps(), request.text(), request.negText(), request.guidance(), request.batchSize(), request.size(), request.seed(), request.scheduler(), progressCallback);
    }

    /**
     * Generates a batch of images from the supplied prompts and parameters.
     * @param numInferenceSteps The number of diffusion inference steps to take (commonly 20-50 for LMS and Euler Ancestral).
     * @param text The text prompt.
     * @param negativeText The negative text prompt which the image should not contain.
     * @param guidanceScale The strength of the classifier-free guidance (i.e., how much should the image represent the text prompt).
     * @param batchSize The number of images to generate.
     * @param size The image size.
     * @param seed The RNG seed, fixing the seed should produce identical images.
     * @param scheduler The diffusion scheduling algorithm.
     * @param progressCallback A supplier which can be used to update a GUI. It is called after each diffusion step with the current step count.
     * @return A list of generated images.
     */
    public List<SDImage> generateImage(int numInferenceSteps, String text, String negativeText, float guidanceScale, int batchSize, ImageSize size, int seed, Schedulers scheduler, Consumer<Integer> progressCallback) {
        try {
            FloatTensor textEmbedding;
            if (guidanceScale < 1.0) {
                logger.info("Generating image for '" + text + "', without guidance");
                textEmbedding = embedder.embedText(text, batchSize);
            } else if (negativeText.isBlank()) {
                logger.info("Generating image for '" + text + "', with guidance");
                textEmbedding = embedder.embedTextAndUncond(text, batchSize);
            } else {
                logger.info("Generating image for '" + text + "', with negative text '" + negativeText + "'");
                textEmbedding = embedder.embedTextAndNegative(text, negativeText, batchSize);
            }
            logger.info("Generated embedding");
            FloatTensor latents = unet.inference(numInferenceSteps, textEmbedding, guidanceScale, batchSize, size.height(), size.width(), seed, progressCallback, scheduler);
            logger.info("Generated latents");
            boolean[] isValid = new boolean[batchSize];
            Arrays.fill(isValid, true);
            List<BufferedImage> image;
            if (safety != null) {
                FloatTensor decoded = vae.decoder(latents);
                List<SafetyChecker.CheckerOutput> checks = safety.check(decoded);
                List<BufferedImage> tmp = VAEDecoder.convertToBufferedImage(decoded);
                image = new ArrayList<>();
                for (int i = 0; i < tmp.size(); i++) {
                    logger.info("SafetyChecker says '" + checks.get(i) + "'");
                    image.add(tmp.get(i));
                    if (checks.get(i) == SafetyChecker.CheckerOutput.NSFW) {
                        isValid[i] = false;
                    }
                }
            } else {
                image = vae.decodeToBufferedImage(latents);
            }
            logger.info("Generated images");
            return wrap(image, numInferenceSteps, text, negativeText, guidanceScale, seed, isValid);
        } catch (OrtException e) {
            throw new IllegalStateException("Model inference failed.", e);
        }
    }

    /**
     * Wraps the output from the image generation in an {@link SDImage} record.
     * @param images The generated images.
     * @param numInferenceSteps The number of inference steps.
     * @param text The text.
     * @param negText The negative text.
     * @param guidanceScale The classifier-free guidance scale.
     * @param seed The RNG seed.
     * @param isValid Is this a valid image?
     * @return A list of SDImages.
     */
    private static List<SDImage> wrap(List<BufferedImage> images, int numInferenceSteps, String text, String negText, float guidanceScale, int seed, boolean[] isValid) {
        List<SDImage> output = new ArrayList<>();

        for (int i = 0; i < images.size(); i++) {
            output.add(new SDImage(images.get(i), text, negText, numInferenceSteps, guidanceScale, seed, i, isValid[i]));
        }

        return output;
    }

    @Override
    public void close() {
        try {
            embedder.close();
            unet.close();
            vae.close();
            if (safety != null) {
                safety.close();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Failed to close sessions.", e);
        }
    }

    /**
     * Constructs a SD4J CPU pipeline from the supplied model path.
     * <p>
     * Expects the following directory structure:
     * <ul>
     *     <li>VAE - $initialPath/vae_decoder/model.onnx</li>
     *     <li>Text Encoder - $initialPath/text_encoder/model.onnx</li>
     *     <li>UNet - $initialPath/unet/model.onnx</li>
     *     <li>Safety checker - $initialPath/safety_checker/model.onnx</li>
     *     <li>Tokenizer - $pwd/text_tokenizer/custom_op_cliptok.onnx</li>
     * </ul>
     * @param initialPath The path to the set of models.
     * @return The SD4J pipeline running on CPUs.
     */
    public static SD4J factory(String initialPath) {
        return factory(initialPath, false);
    }

    /**
     * Constructs a SD4J pipeline from the supplied model path, optionally on GPUs.
     * <p>
     * Expects the following directory structure:
     * <ul>
     *     <li>VAE - $initialPath/vae_decoder/model.onnx</li>
     *     <li>Text Encoder - $initialPath/text_encoder/model.onnx</li>
     *     <li>UNet - $initialPath/unet/model.onnx</li>
     *     <li>Safety checker - $initialPath/safety_checker/model.onnx</li>
     *     <li>Tokenizer - $pwd/text_tokenizer/custom_op_cliptok.onnx</li>
     * </ul>
     * @param initialPath The path to the set of models.
     * @param useCUDA Should the text encoder, unet, vae and safety checker be run on GPU?
     * @return The SD4J pipeline.
     */
    public static SD4J factory(String initialPath, boolean useCUDA) {
        return factory(new SD4JConfig(initialPath, useCUDA ? ExecutionProvider.CUDA : ExecutionProvider.CPU, 0, ModelType.SD1_5));
    }

    /**
     * Constructs a SD4J pipeline from the supplied model path, optionally on GPUs.
     * <p>
     * Expects the following directory structure:
     * <ul>
     *     <li>VAE - $initialPath/vae_decoder/model.onnx</li>
     *     <li>Text Encoder - $initialPath/text_encoder/model.onnx</li>
     *     <li>UNet - $initialPath/unet/model.onnx</li>
     *     <li>Safety checker - $initialPath/safety_checker/model.onnx</li>
     *     <li>Tokenizer - $pwd/text_tokenizer/custom_op_cliptok.onnx</li>
     * </ul>
     * @param config The SD4J configuration.
     * @return The SD4J pipeline.
     */
    public static SD4J factory(SD4JConfig config) {
        var vaePath = Path.of(config.modelPath(), "/vae_decoder/model.onnx");
        var encoderPath = Path.of(config.modelPath(), "/text_encoder/model.onnx");
        var unetPath = Path.of(config.modelPath(), "/unet/model.onnx");
        var safetyPath = Path.of(config.modelPath(), "/safety_checker/model.onnx");
        var tokenizerPath = Path.of("text_tokenizer/custom_op_cliptok.onnx");

        try {
            // Initialize the library
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            env.setTelemetry(false);
            final int deviceId = config.id();
            Supplier<OrtSession.SessionOptions> optsSupplier = switch (config.provider) {
                case CUDA -> () -> {
                    try {
                        var opts = new OrtSession.SessionOptions();
                        var cudaOpts = new OrtCUDAProviderOptions(deviceId);
                        cudaOpts.add("arena_extend_strategy","kSameAsRequested");
                        cudaOpts.add("cudnn_conv_algo_search","DEFAULT");
                        cudaOpts.add("do_copy_in_default_stream","1");
                        cudaOpts.add("cudnn_conv_use_max_workspace","1");
                        cudaOpts.add("cudnn_conv1d_pad_to_nc1d","1");
                        opts.addCUDA(cudaOpts);
                        return opts;
                    } catch (OrtException e) {
                        throw new IllegalStateException("Failed to create options.", e);
                    }
                };
                case CORE_ML -> () -> {
                    try {
                        var opts = new OrtSession.SessionOptions();
                        opts.setInterOpNumThreads(0);
                        opts.setIntraOpNumThreads(0);
                        opts.addCoreML();
                        return opts;
                    } catch (OrtException e) {
                        throw new IllegalStateException("Failed to construct session options", e);
                    }
                };
                case DIRECT_ML -> () -> {
                    try {
                        var opts = new OrtSession.SessionOptions();
                        opts.setInterOpNumThreads(0);
                        opts.setIntraOpNumThreads(0);
                        opts.addDirectML(deviceId);
                        return opts;
                    } catch (OrtException e) {
                        throw new IllegalStateException("Failed to construct session options", e);
                    }
                };
                case CPU -> () -> {
                    try {
                        var opts = new OrtSession.SessionOptions();
                        opts.setInterOpNumThreads(0);
                        opts.setIntraOpNumThreads(0);
                        return opts;
                    } catch (OrtException e) {
                        throw new IllegalStateException("Failed to construct session options", e);
                    }
                };
            };
            TextEmbedder embedder = new TextEmbedder(tokenizerPath, encoderPath, optsSupplier.get(), config.type.textDimSize);
            logger.info("Loaded embedder from " + encoderPath);
            UNet unet = new UNet(unetPath, optsSupplier.get());
            logger.info("Loaded unet from " + unetPath);
            VAEDecoder vae = new VAEDecoder(vaePath, optsSupplier.get());
            logger.info("Loaded vae from " + vaePath);
            SafetyChecker safety;
            if (safetyPath.toFile().exists()) {
                safety = new SafetyChecker(safetyPath, optsSupplier.get());
                logger.info("Created safety");
            } else {
                safety = null;
                logger.info("No safety found");
            }
            return new SD4J(embedder, unet, vae, safety);
        } catch (OrtException e) {
            throw new IllegalStateException("Failed to instantiate SD4J pipeline", e);
        }
    }

    /**
     * An image generated from Stable Diffusion, along with the input text and other inference properties.
     * @param image The image.
     * @param text The text used to control generation.
     * @param numInferenceSteps The number of diffusion inference steps.
     * @param guidanceScale The strength of the guidance.
     * @param seed The RNG seed.
     * @param batchId The id number within the batch.
     */
    public record SDImage(BufferedImage image, String text, String negText, int numInferenceSteps, float guidanceScale, int seed, int batchId, boolean isValid) {}

    /**
     * Image size.
     * @param height Height in pixels.
     * @param width Width in pixels.
     */
    public record ImageSize(int height, int width) {
        /**
         * Creates a square image size.
         * @param size The height and width.
         */
        public ImageSize(int size) {
            this(size, size);
        }

        @Override
        public String toString() {
            return "[" + height + ", " + width + "]";
        }
    }

    /**
     * A image creation request.
     * @param text The image text.
     * @param negText The image negative text.
     * @param steps The number of diffusion steps.
     * @param guidance The strength of the classifier-free guidance.
     * @param seed The RNG seed used to initialize the image (and any ancestral sampling noise).
     * @param size The requested image size.
     * @param scheduler The scheduling algorithm.
     * @param batchSize The batch size.
     */
    public record Request(String text, String negText, int steps, float guidance, int seed, ImageSize size, Schedulers scheduler, int batchSize) {
        Request(String text, String negText, String stepsStr, String guidanceStr, String seedStr, ImageSize size, Schedulers scheduler, String batchSize) {
            this(text.strip(), negText.strip(), Integer.parseInt(stepsStr), Float.parseFloat(guidanceStr), Integer.parseInt(seedStr), size, scheduler, Integer.parseInt(batchSize));
        }
    }

    /**
     * Supported execution providers.
     */
    public enum ExecutionProvider {
        /**
         * CPU.
         */
        CPU,
        /**
         * Apple's Core ML.
         */
        CORE_ML,
        /**
         * Nvidia GPUs.
         */
        CUDA,
        /**
         * Windows DirectML devices.
         */
        DIRECT_ML;

        /**
         * Looks up an execution provider returning the enum or throwing {@link IllegalArgumentException} if it's unknown.
         * @param name The ep to lookup.
         * @return The enum value.
         */
        public static ExecutionProvider lookup(String name) {
            String lower = name.toLowerCase(Locale.US);
            return switch (lower) {
                case "cpu", "" -> CPU;
                case "coreml", "core_ml", "core-ml" -> CORE_ML;
                case "cuda" -> CUDA;
                case "directml", "direct_ml", "direct-ml" -> DIRECT_ML;
                default -> { throw new IllegalArgumentException("Unknown execution provider '" + name + "'"); }
            };
        }
    }

    /**
     * The type of Stable Diffusion model.
     */
    public enum ModelType {
        SD1_5(TextEmbedder.SD_1_5_DIM_SIZE),
        SD2(TextEmbedder.SD_2_DIM_SIZE);

        /**
         * The text dimension size.
         */
        public final int textDimSize;

        private ModelType(int textDimSize) {
            this.textDimSize = textDimSize;
        }

        /**
         * Looks up the model type returning the enum or throwing {@link IllegalArgumentException} if it's unknown.
         * @param name The model type to lookup.
         * @return The enum value.
         */
        public static ModelType lookup(String name) {
            String lower = name.toLowerCase(Locale.US);
            return switch (lower) {
                case "sdv1.5", "sd15", "sd1.5", "sd1_5", "sd1", "sdv1" -> SD1_5;
                case "sdv2", "sdv21", "sdv2.1", "sd-turbo", "sd_turbo" -> SD2;
                default -> { throw new IllegalArgumentException("Unknown model type '" + name + "'"); }
            };
        }
    }

    /**
     * Record for the SD4J configuration.
     * @param modelPath The path to the onnx models.
     * @param provider The execution provider to use.
     * @param id The device id.
     */
    public record SD4JConfig(String modelPath, ExecutionProvider provider, int id, ModelType type) {
        /**
         * Parses the arguments into a config.
         * @param args The arguments.
         * @return A SD4J config.
         */
        public static Optional<SD4JConfig> parseArgs(String[] args) {
            String modelPath = "";
            String ep = "";
            String modelType = "sd1.5";
            int id = 0;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "--usage" -> {
                        return Optional.empty();
                    }
                    case "--model-path", "-p" -> {
                        // check if there's another argument, otherwise return empty
                        if (i == args.length - 1) {
                            // No model path
                            return Optional.empty();
                        } else {
                            // Consume argument
                            i++;
                            modelPath = args[i];
                        }
                    }
                    case "--execution-provider", "--ep" -> {
                        // check if there's another argument, otherwise return empty
                        if (i == args.length - 1) {
                            // No provider
                            return Optional.empty();
                        } else {
                            // Consume argument
                            i++;
                            ep = args[i];
                        }
                    }
                    case "--device-id" -> {
                        // check if there's another argument, otherwise return empty
                        if (i == args.length - 1) {
                            // No id
                            return Optional.empty();
                        } else {
                            // Consume argument
                            i++;
                            id = Integer.parseInt(args[i]);
                        }
                    }
                    case "--model-type", "-m" -> {
                        // check if there's another argument, otherwise return empty
                        if (i == args.length - 1) {
                            // No provider
                            return Optional.empty();
                        } else {
                            // Consume argument
                            i++;
                            modelType = args[i];
                        }
                    }
                    default -> {
                        // Unexpected argument
                        logger.warning("Unexpected argument '" + args[i] + "'");
                        return Optional.empty();
                    }
                }
            }
            return Optional.of(new SD4JConfig(modelPath, ExecutionProvider.lookup(ep), id, ModelType.lookup(modelType)));
        }

        /**
         * Help string for the config arguments.
         * @return The help string.
         */
        public static String help() {
            return "SD4J --model-path <model-path> --execution-provider {CUDA,CoreML,DirectML,CPU} (optional --device-id <int> --model-type <sd1.5 or sd2>)";
        }
    }
}
