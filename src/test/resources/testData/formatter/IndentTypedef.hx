typedef  Point2D = {        x:Int,       y:Int      }
typedef  Point3D = {
             > Point2D,
z:Int
}
typedef FormattingTest = {
            var x = 0 ;
        var y = 0 ;
var z = 0 ;

function dummy(point:Point3D) {
x= point.x;
    y= point.y;
    z  = point.z;
}
}

class MyClass<T:{x:Int,     y:Int}>
{
    var point:T = {x:1,y:2};
}