class T

class A {
    companion object {
        fun T.fooExtension() {}
        val T.fooProperty get() = 10
    }
}

fun T.usage() {
    foo<caret>
}

// EXIST: { lookupString: "fooExtension", itemText: "fooExtension" }
// EXIST: { lookupString: "fooProperty", itemText: "fooProperty" }