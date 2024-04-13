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
import groovy.transform.Field

import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Paths
import javax.swing.*

@Field def cli = new CliBuilder(usage: 'qrcat.groovy', header: '''
    groovy qrcat.groovy [-b BYTES] [send] < FILE
    groovy qrcat.groovy [-a] [recv] > FILE

Options:
''')
cli.a(longOpt: 'autoclick',
      'Autoclick mode: receiver will click on sender to go to next frame automatically. ' +
      'Without autoclick mode, sender has to be moved to next frame with a mouse click or spacebar.')
cli.b(longOpt: 'bytes', args: 1, argName: 'BYTES',
      'Number of bytes per frame. Default is 2000 bytes.')
@Field def opts
opts = cli.parse(args)

if (opts.bytes) SendChunker.bytes = opts.bytes.toInteger()

@Field def err = System.err

def usage() {
    cli.usage()
    System.exit(2)
}

def test(String... args) {
    def pb = new ProcessBuilder(['test'] + (args as List))
    pb.inheritIO()
    def p = pb.start()
    p.waitFor()
    return p.exitValue()
}

if (opts.arguments().isEmpty()) {
    try {
        test('-e', '.')
    } catch (IOException e) {
        usage()
    }
    def t0 = test('-t', '0')
    def t1 = test('-t', '1')
    if (t0 == t1) usage()
    else if (t0 == 1 && t1 == 0) send()
    else if (t0 == 0 && t1 == 1) recv()
    else usage()
}
else if (opts.arguments().size() != 1) usage()
else if (opts.arguments().get(0) == 'send') send()
else if (opts.arguments().get(0) == 'recv') recv()
else usage()

class SendChunker {
    static int bytes = 2000

    @Bindable ImageIcon imageIcon
    boolean eof = false

    private byte[] chunk
    private Random r = new Random()
    private byte seq = 0

    def readChunk() {
        chunk = System.in.readNBytes(bytes)
        seq = ((seq == 99) ? 1 : (seq + 1))
        refresh()
    }

    def refresh() {
        String contents
        if (chunk) {
            byte[] bytes = Arrays.copyOf(chunk, chunk.length + 2)
            bytes[bytes.length - 2] = r.nextInt(127) as byte
            bytes[bytes.length - 1] = seq
            contents = bytes.encodeBase64().toString()
        } else {
            contents = "\004"
            eof = true
        }

        if (imageIcon) System.err.print(' ')
        System.err.print(eof ? 'eof' : seq)

        def bitMatrix = new QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 600, 600)
        def bufferedImage = MatrixToImageWriter.toBufferedImage(bitMatrix)
        setImageIcon(new ImageIcon(bufferedImage))
    }
}

def send() {
    def sendChunker = new SendChunker()
    sendChunker.readChunk()

    def t = 0L

    def action1 = {
        if (sendChunker.eof) {
            err.println()
            System.exit(0)
        }
        sendChunker.readChunk()
        t = System.currentTimeMillis()
    }

    def action2 = {
        sendChunker.refresh()
        err.print('?')
    }

    def swing = new SwingBuilder()
    swing.edt {
        frame(title: 'qrcat', defaultCloseOperation: JFrame.EXIT_ON_CLOSE, pack: true, show: true, id: 'frame') {
            borderLayout()
            label(
                icon: bind(source: sendChunker, sourceProperty: 'imageIcon'),
                mouseClicked: { e ->
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                        action1()
                    }
                    if (e.getButton() == MouseEvent.BUTTON3 && e.getClickCount() == 1) {
                        action2()
                    }
                }
            )
        }
    }
    swing.frame.addKeyListener([ keyPressed: { e ->
        if (e.getKeyCode() in [KeyEvent.VK_ESCAPE, KeyEvent.VK_Q]) {
            System.exit(3)
        }
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            action1()
        }
        if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
            action2()
        }
    }] as KeyAdapter)

    def r = new Random()
    new Timer(1000, {
        if (t > 0 && System.currentTimeMillis() - t > 1000) {
            def p = swing.frame.getLocationOnScreen()
            p.x += (p.x < 20 ? 1 : p.x > 100 ? -1 : r.nextBoolean() ? 1 : -1) * r.nextInt(10)
            p.y += (p.y < 20 ? 1 : p.y > 100 ? -1 : r.nextBoolean() ? 1 : -1) * r.nextInt(10)
            swing.frame.setLocation(p)
            err.print('.')
        }
    }).start()
}

def recv() {
    def toolkit = Toolkit.getDefaultToolkit()
    def robot = new Robot()
    def prevText = ''
    def prevSeq = 0
    def sameTextCount = 0
    def points = null

    def click = { button ->
        robot.mouseMove(
            (int) points.collect { point -> point.getX() }.average(),
            (int) points.collect { point -> point.getY() }.average())
        robot.mousePress(button)
        robot.delay(100)
        robot.mouseRelease(button)
        robot.delay(400)
    }

    while (true) {
        def result
        def attempt = 0
        while (true) {
            try {
                def screenRect = new Rectangle(toolkit.getScreenSize())
                def image = robot.createScreenCapture(screenRect) // does not include mouse cursor
                def binaryBitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))
                result = new QRCodeReader().decode(binaryBitmap)
                break
            } catch (ReaderException e) {
                // com.google.zxing.FormatException
                // com.google.zxing.NotFoundException
                if (e instanceof com.google.zxing.FormatException) {
                    if (opts.autoclick) {
                        click(InputEvent.BUTTON3_DOWN_MASK)
                    }
                    err.print(' ?')
                } else {
                    err.print('.')
                }
                if (attempt > 60) {
                    err.println()
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
            err.print('!')
        }
        sameTextCount = 0

        points = result.getResultPoints()
        if (opts.autoclick) {
            click(InputEvent.BUTTON1_DOWN_MASK)
        }

        if (prevText == nextText)
            continue
        prevText = nextText

        if (nextText == "\004") {
            break
        } else {
            byte[] bytes = nextText.decodeBase64()
            byte seq = bytes[bytes.length - 1]

            def d = seq - prevSeq
            prevSeq = seq
            if (d < 0) d += 99
            if (d == 0) {
                continue
            } else if (d != 1) {
                if (prevSeq) err.print(' ')
                err.print('+')
                err.print(d)
                err.print('!')
            }

            System.out.write(bytes, 0, bytes.length - 2)

            if (seq > 1) err.print(' ')
            err.print(seq)
        }
    }
    err.println(' eof')
}
