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
            contents = "\004"
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

    def t = 0L

    def swing = new SwingBuilder()
    swing.edt {
        frame(title: "qrcat", defaultCloseOperation: JFrame.EXIT_ON_CLOSE, pack: true, show: true, id: "frame") {
            borderLayout()
            label(
                icon: bind(source: sendChunker, sourceProperty: 'imageIcon'),
                mouseClicked: { e ->
                    if (e.getClickCount() == 1) {
                        if (sendChunker.eof) {
                            System.err.println()
                            System.exit(0)
                        }
                        System.err.print('.')
                        sendChunker.readChunk()
                        t = System.currentTimeMillis()
                    }
                }
            )
        }
    }

    def r = new Random()
    new Timer(3000, {
        if (t > 0 && System.currentTimeMillis() - t > 3000) {
            def p = swing.frame.getLocationOnScreen()
            p.x += (p.x < 20 ? 1 : p.x > 100 ? -1 : r.nextBoolean() ? 1 : -1) * r.nextInt(10)
            p.y += (p.y < 20 ? 1 : p.y > 100 ? -1 : r.nextBoolean() ? 1 : -1) * r.nextInt(10)
            swing.frame.setLocation(p)
            System.err.print('!')
        }
    }).start()
}

def recv() {
    def robot = new Robot()
    def prevText = ""
    def sameTextCount = 0
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
                if (attempt > 60) {
                    throw e
                }
                attempt++
                robot.delay(1000)
            }
        }

        def nextText = result.getText()
        if (prevText == nextText) {
            robot.delay(200)
            sameTextCount++
            if (sameTextCount < 10)
                continue
            System.err.print("!")
        }
        sameTextCount = 0

        robot.mouseMove(
            (int)result.getResultPoints().collect { point -> point.getX() }.average(),
            (int)result.getResultPoints().collect { point -> point.getY() }.average())
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.delay(100)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        robot.delay(400)

        if (prevText == nextText)
            continue
        prevText = nextText

        if (nextText == "\004") {
            break
        } else {
            def bytes = nextText.decodeBase64()
            System.out.write(bytes, 0, bytes.length)
            System.err.print(".")
        }
    }
    System.err.println()
}
