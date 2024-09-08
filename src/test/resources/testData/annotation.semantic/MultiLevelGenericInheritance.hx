package;
//Verify that constraints and typeParameters are correctly resolved in hierarchy
// level 3 has type parameters but these should be ignored in level 1 as they are not passed on to subclass
// Level 1 does not define constraints but since we are calling from level 3 with level 2 in the inherentace hierachy,
// type parameters and constrains from level 2 should be passed on to level 1 members.
class Level3Class<T:String> extends Level2Class {
    public function new() {

        //CORRECT
        var paramA:Int = this.testTypeParam(1);
        var paramB:Int = testTypeParam(1);
        var fieldA:Int = this.field;
        var fieldB:Int = field;
        var fnA:Int -> Int = this.testTypeParam;

        // WRONG
        var <error descr="Incompatible type: Int should be String">paramA:String = this.testTypeParam(<error descr="Type mismatch (Expected: 'Int' got: 'String')">"1"</error>)</error>;
        var <error descr="Incompatible type: Int should be String">paramB:String = testTypeParam(<error descr="Type mismatch (Expected: 'Int' got: 'String')">"1"</error>)</error>;
        var <error descr="Incompatible type: Int should be String">fieldA:String = this.field</error>;
        var <error descr="Incompatible type: Int should be String">fieldB:String = field</error>;
        var <error descr="Incompatible type: Int->Int should be String->String">fnA:String -> String = this.testTypeParam</error>;

    }
}
class Level2Class extends Level1Class<Int> {
    public function new() {}
}

class Level1Class<T>  {
    var field:T;

    public function new() {
    }

    public function testTypeParam(t:T):T {
        return t;
    }
}

