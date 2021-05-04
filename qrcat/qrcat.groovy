#!/usr/bin/env groovy

@Grab('com.google.zxing:core:3.4.1')
@Grab('com.google.zxing:javase:3.4.1')
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ReaderException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

import groovy.beans.Bindable
import groovy.swing.SwingBuilder

import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Paths
import javax.swing.*

def usage() {
    System.err.println("Usage: groovy qrcat.groovy send < FILE")
    System.err.println("       groovy qrcat.groovy recv > FILE")
    System.exit(2)
}

if (args.length != 1) usage()
else if (args[0] == "send") send()
else if (args[0] == "recv") recv()
else usage()

class SendChunker {
    @Bindable ImageIcon imageIcon
    boolean eof = false

    def readChunk() {
        byte[] bytes = System.in.readNBytes(2000)

        String contents
        if (bytes) {
            contents = bytes.encodeBase64().toString()
        } else {
            contents = "\004";
            eof = true
        }

        def bitMatrix = new QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 600, 600)
        def bufferedImage = MatrixToImageWriter.toBufferedImage(bitMatrix)
        setImageIcon(new ImageIcon(bufferedImage))
    }
}

def send() {
    def sendChunker = new SendChunker()
    sendChunker.readChunk()

    new SwingBuilder().edt {
        frame(title: "qrcat", defaultCloseOperation: JFrame.EXIT_ON_CLOSE, pack: true, show: true) {
            borderLayout()
            label(
                icon: bind(source: sendChunker, sourceProperty: 'imageIcon'),
                mouseClicked: { e ->
                    if (e.getClickCount() == 2) {
                        if (sendChunker.eof) {
                            System.err.println()
                            System.exit(0)
                        }
                        System.err.print('.')
                        sendChunker.readChunk()
                    }
                }
            )
        }
    }
}

def recv() {
    def robot = new Robot()
    while (true) {
        def result
        def attempt = 0
        while (true) {
            try {
                def screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())
                def image = robot.createScreenCapture(screenRect)
                def binaryBitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))
                result = new QRCodeReader().decode(binaryBitmap)
                break
            } catch (ReaderException e) {
                System.err.print("z")
                if (attempt > 30) {
                    throw e
                }
                attempt++
                robot.delay(1000)
            }
        }

        robot.mouseMove(
            (int)result.getResultPoints().collect { point -> point.getX() }.average(),
            (int)result.getResultPoints().collect { point -> point.getY() }.average())
        for (n in 1..2) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.delay(50)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.delay(100)
        }

        def text = result.getText()
        if (text == "\004") {
            break
        } else {
            print(new String(result.getText().decodeBase64()))
            System.err.print(".")
        }
    }
    System.err.println()
}
