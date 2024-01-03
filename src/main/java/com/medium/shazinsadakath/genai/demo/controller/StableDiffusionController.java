package com.medium.shazinsadakath.genai.demo.controller;

import com.medium.shazinsadakath.genai.demo.sd4j.SD4J;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.imageio.ImageIO;
import java.io.*;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/stablediffusion")
public class StableDiffusionController implements CommandLineRunner {

    private Optional<SD4J.SD4JConfig> config;

    private SD4J sd;

    @GetMapping
    public StreamingResponseBody getImage(@RequestParam String q) {


        String text = q;

        int seed = 42;
        List<SD4J.SDImage> images = getSd().generateImage(5, text, "", 7.5f, 1, new SD4J.ImageSize(512, 512), seed);

        return os -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(images.get(0).image(), "png", bos);
            readAndWrite(new ByteArrayInputStream(bos.toByteArray()), os);
        };
    }

    private void readAndWrite(final InputStream is, OutputStream os)
            throws IOException {
        byte[] data = new byte[2048];
        int read = 0;
        while ((read = is.read(data)) > 0) {
            os.write(data, 0, read);
        }
        os.flush();
    }

    @Override
    public void run(String... args) throws Exception {
        config = SD4J.SD4JConfig.parseArgs(args);

        sd = SD4J.factory(config.get());
    }

    private SD4J getSd() {
        if (sd == null) {
            sd = SD4J.factory(config.get());
        }
        return sd;
    }
}
