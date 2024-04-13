#!/usr/bin/env groovy

def test(fd) {
    //def p = "test -t ${fd}".execute()
    //p.waitFor()
    //println("test -t ${fd} => " + p.exitValue())

    def pb = new ProcessBuilder("test", "-t", "$fd")
    pb.inheritIO()
    def p = pb.start()
    p.waitFor()
    println("test -t ${fd} => " + p.exitValue())
}

test(0)
test(1)
test(2)
