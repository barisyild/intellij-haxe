package;
class Test {
  public function new() {
    // The following is OK because an Int unifies with a Float
    var f:Float = (10:Int);
    //  compiler error Float should be Int
    var <error descr="Incompatible type: Float should be Int">i:Int = (10:Float)</error>;
  }
}