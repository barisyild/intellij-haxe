// ///////////////////////////
// Haxe 3 compatible
// ///////////////////////////
// extern enum Void { }
// extern class Float { }
// extern class Int extends Float { }
// typedef Null<T> = T;
// extern enum Bool {
//   true;
//   false;
// }
// extern class Dynamic<T> { }
// typedef Iterator<T> = {
//   function hasNext() : Bool;
//   function next() : T;
// }
// typedef Iterable<T> = {
//   function iterator() : Iterator<T>;
// }
// extern interface ArrayAccess<T> { }

// //////////////////////////
//
// Haxe 4 compatible
//
// //////////////////////////
@:coreType abstract Void { }
@:coreType @:notNull @:runtimeValue abstract Float { }
@:coreType @:notNull @:runtimeValue abstract Int to Float { }
@:coreType @:notNull @:runtimeValue abstract Single to Float from Float {} //(java || cs || hl || cpp)
@:forward @:coreType abstract Null<T> from T to T { }
@:coreType @:notNull @:runtimeValue @:enum abstract Bool {
  false = 0;
  true = !false;
}
abstract Dynamic<T> { }
typedef Iterator<T> = {
  function hasNext() : Bool;
  function next() : T;
}
typedef Iterable<T> = {
  function iterator() : Iterator<T>;
}
typedef KeyValueIterator<K,V> = Iterator<{key:K, value:V}>;
typedef KeyValueIterable<K,V> = {
    function keyValueIterator():KeyValueIterator<K,V>;
}
extern interface ArrayAccess<T> { }
