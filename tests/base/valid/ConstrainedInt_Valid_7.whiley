import println from whiley.lang.System

// this is a comment!
define nat as int
define num as {1,2,3,4}

string f(num x):
    y = x
    return Any.toString(y)

string g(int x, nat z):
    return f(z)

void ::main(System.Console sys):
    sys.out.println(g(1,3))
