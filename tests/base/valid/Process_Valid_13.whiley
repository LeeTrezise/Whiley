import whiley.lang.*
import whiley.lang.System:*

define Queue as process { [int] items }
	 
int Queue::get():
    item = this.items[0]
    this.items = this.items[1..]
    return item
	 
void Queue::put(int item):
    this.items = this.items + [item]

bool Queue::isEmpty():
    return |this.items| == 0

void ::main(System sys, [string] args):
    items = [1,2,3,4,5,6,7,8,9,10]
    q = spawn { items: [] }
    // first, push items into queue
    for item in items:
        q.put(item)
        sys.out.println("PUT: " + String.str(item))
    // second, retrieve items back from queue
    while !q.isEmpty():
        sys.out.println("GET: " + String.str(q.get()))
    