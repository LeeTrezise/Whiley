

void f({int} ls) requires no { i in ls | i <= 0}:
    debug Any.toString(ls)

void ::main(System.Console sys):
    f({0,1,2,3})
