package com.socialflux.profile.storage;

import com.socialflux.profile.config.ProfileProperties;
import com.socialflux.profile.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Valida e RE-CODIFICA a imagem enviada: confere o tamanho e os bytes mágicos (PNG/JPEG), lê as dimensões ANTES de
 * decodificar (bloqueia "image bombs"), recorta o centro em quadrado e gera sempre um JPEG novo 256×256, o que descarta
 * metadados (EXIF), canal alfa e qualquer conteúdo embutido no arquivo original.
 */
@Component
public class AvatarProcessor {

    public static final int SIZE = 256;
    private static final float QUALITY = 0.85f;

    private final long maxBytes;
    private final long maxPixels;

    public AvatarProcessor(ProfileProperties properties) {
        this.maxBytes = properties.storage().maxBytes();
        this.maxPixels = properties.storage().maxPixels();
        System.setProperty("java.awt.headless", "true");
    }

    public byte[] process(byte[] data) {
        if (data == null || data.length == 0) {
            throw ApiException.badRequest("missing_file", "Arquivo de imagem vazio");
        }
        if (data.length > maxBytes) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "A imagem excede o tamanho máximo permitido");
        }
        if (!hasImageMagic(data)) {
            throw invalid();
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > maxPixels) {
                    throw ApiException.badRequest("image_too_large", "A imagem tem dimensões grandes demais");
                }
                BufferedImage source = reader.read(0);
                return encode(squareThumbnail(source));
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private static boolean hasImageMagic(byte[] d) {
        boolean png = d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A;
        boolean jpeg = d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
        return png || jpeg;
    }

    private static BufferedImage squareThumbnail(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);                       // transparência vira fundo branco
            g.fillRect(0, 0, SIZE, SIZE);
            g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("Sem codificador JPEG");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("invalid_image", "Formato de imagem não suportado (use PNG ou JPEG)");
    }
}
