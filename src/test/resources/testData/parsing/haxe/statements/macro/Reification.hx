package ;
import haxe.macro.Expr;

class MacroTest {

    macro public function macroArrayReification(inputArray:Expr) {
        return ($a{ inputArray });
    }

    macro public function macroValueReification():Expr<Int> {
        var value = 1;
        return macro { $v{ value } };
    }

    static public function macroBlockReification(a:Array<Expr>) {
        return macro $b{a};
    }

    static public function macroIdentifierReification(identifier:String) {
        return macro $i{identifier};
    }

    static public function macroFieldReification(field:Array<String>) {
        return macro ($p{field});
    }

    static public function macroExpReification(e:Expr):Dynamic {
        return macro (function() return $e);
    }
}
