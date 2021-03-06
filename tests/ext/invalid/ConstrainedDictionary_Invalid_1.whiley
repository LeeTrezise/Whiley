import * from whiley.lang.*

define nat as int where $>=0
define dict as {int=>nat}

int f(int key, dict d):
    return d[key]

public void ::main(System.Console sys):
    d = {-2=>20,-1=>-1,0=>0,1=>10,2=>20}
    sys.out.println(f(-2,d))
    sys.out.println(f(-1,d))
    sys.out.println(f(-0,d))
    sys.out.println(f(1,d))
    sys.out.println(f(2,d))
