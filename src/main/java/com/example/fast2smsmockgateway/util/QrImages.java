package com.example.fast2smsmockgateway.util;

import com.example.fast2smsmockgateway.dtos.response.QrImage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class QrImages {
    private QrImages() {
    }

    public static QrImage render(String link, String format) {
        try {
            BitMatrix bits = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0);
            return "SVG".equals(format) ? svg(bits) : png(bits);
        } catch (WriterException exception) {
            throw new IllegalStateException("Unable to encode the sandbox QR link", exception);
        }
    }

    private static QrImage svg(BitMatrix bits) {
        int size = bits.getWidth();
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(size).append(' ').append(size).append("\" shape-rendering=\"crispEdges\">")
                .append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/><path d=\"");
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (bits.get(x, y)) {
                    svg.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        svg.append("\"/></svg>");
        return new QrImage("image/svg+xml", svg.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static QrImage png(BitMatrix bits) {
        int size = bits.getWidth();
        int scale = 6;
        BufferedImage image = new BufferedImage(size * scale, size * scale, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, bits.get(x / scale, y / scale) ? 0xff000000 : 0xffffffff);
            }
        }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", output);
            return new QrImage("image/png", output.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
