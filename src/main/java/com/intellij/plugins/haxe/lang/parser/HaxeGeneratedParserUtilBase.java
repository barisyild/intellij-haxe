/*
 * Copyright 2017-2017 Ilya Malanin
 * Copyright 2017 Eric Bishton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.lang.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.WhitespaceSkippedCallback;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Stack;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

public class HaxeGeneratedParserUtilBase extends GeneratedParserUtilBase {
  private static boolean whiteSpaceSkipped = false;

  private static boolean parseOperator(PsiBuilder builder_, IElementType operator, IElementType... tokens) {
    final PsiBuilder.Marker marker_ = builder_.mark();

    whiteSpaceSkipped = false;

    builder_.setWhitespaceSkippedCallback(new WhitespaceSkippedCallback() {
      @Override
      public void onSkip(IElementType type, int i, int i1) {
        whiteSpaceSkipped = true;
      }
    });

    for (IElementType token : tokens) {
      if (!consumeTokenFast(builder_, token) || whiteSpaceSkipped) {
        marker_.rollbackTo();
        builder_.setWhitespaceSkippedCallback(null);
        return false;
      }
    }

    builder_.setWhitespaceSkippedCallback(null);
    marker_.collapse(operator);
    return true;
  }
  private static boolean parseOperatorNotFollowedBy(PsiBuilder builder_, IElementType operator, IElementType token) {
    final PsiBuilder.Marker marker_ = builder_.mark();

    IElementType fistElement = builder_.lookAhead(0);
    IElementType secondElement = builder_.lookAhead(1);
    if (fistElement == operator  && secondElement != token) {
      if (consumeTokenFast(builder_, operator)) {
        marker_.collapse(operator);
        return true;
      }

    }

    marker_.rollbackTo();
    builder_.setWhitespaceSkippedCallback(null);
    return false;
  }

  public static boolean shiftRight(PsiBuilder builder_, int level_) {
    return parseOperator(builder_, OSHIFT_RIGHT, OGREATER, OGREATER);
  }

  public static boolean shiftRightAssign(PsiBuilder builder_, int level_) {
    return parseOperator(builder_, OSHIFT_RIGHT_ASSIGN, OGREATER, OGREATER, OASSIGN);
  }

  public static boolean unsignedShiftRight(PsiBuilder builder_, int level_) {
    return parseOperator(builder_, OUNSIGNED_SHIFT_RIGHT, OGREATER, OGREATER, OGREATER);
  }

  public static boolean unsignedShiftRightAssign(PsiBuilder builder_, int level_) {
    return parseOperator(builder_, OUNSIGNED_SHIFT_RIGHT_ASSIGN, OGREATER, OGREATER, OGREATER, OASSIGN);
  }

  public static boolean gtEq(PsiBuilder builder_, int level_) {
    return parseOperator(builder_, OGREATER_OR_EQUAL, OGREATER, OASSIGN);
  }

  public static boolean ternary(PsiBuilder builder_, int level_) {
    return parseOperatorNotFollowedBy(builder_, OQUEST, ODOT);
  }


  /**
   * Make a semi-colon optional in the case that it's preceded by a block statement.
   *
   */
  public static boolean semicolonUnlessPrecededByStatement(PsiBuilder builder_, int level) {
    if (consumeTokenFast(builder_, OSEMI)) {
      return true;
    }
    int i = -1;
    IElementType previousType = builder_.rawLookup(i);
    while (null != previousType && isWhitespaceOrComment(builder_, previousType)) {
      previousType = builder_.rawLookup(--i);
    }
    if (previousType == HaxeTokenTypes.PRCURLY || previousType == HaxeTokenTypes.OSEMI) {
      return true;
    }
    /*
      macro value expressions can be "normal" expressions but should be treated as a single value
      so the same way an string or int argument does not need a trailing ; in a method call
      a macro value like  `macro var c = "test"` should not have a ; at the ned either.
     */

    Stack<Boolean> stack = getFlagStack(builder_, SEMICOLON_RULE_STATE);
    if (!stack.isEmpty()) {
      Boolean value = stack.peek();
      if (value != null) {
        return !value;
      }
    }

    builder_.error(HaxeBundle.message("parsing.error.missing.semi.colon"));
    return false;
  }

  /**
  * guarded statements followed by else statement does not need semi
  */
  public static boolean semicolonUnlessFollowedByElseStatement(PsiBuilder builder_, int level) {
    if (consumeTokenFast(builder_, OSEMI)) {
      return true;
    }
    int i = 0;
    IElementType nextType = builder_.rawLookup(i);
    while (null != nextType && isWhitespaceOrComment(builder_, nextType)) {
      nextType = builder_.rawLookup(i++);
    }

    //guarded statement does not need semicolon if followed by "else"
    return nextType == ELSE_STATEMENT;
  }
  // hopefully faster way to stop unnecessary parsing attempts when not reification
  public static boolean canBeReification(PsiBuilder builder_, int level) {
    IElementType type = builder_.rawLookup(0) ;
    return type == DOLLAR || type == MACRO_ID;
  }

  private static final com.intellij.openapi.util.Key<Stack<Boolean>> SEMICOLON_RULE_STATE = new Key<>("SEMICOLON_RULE_STATE");
  private static final com.intellij.openapi.util.Key<Stack<Boolean>> COLLECTION_INITIALIZER_STATE = new Key<>("COLLECTION_INITIALIZER_STATE");

  private static @NotNull Stack<Boolean> getFlagStack(PsiBuilder builder_, Key<Stack<Boolean>> flag) {
    Stack<Boolean> stack = builder_.getUserData(flag);
    if (stack == null) {
      stack = new Stack<>();
      builder_.putUserData(flag, stack);
    }
    return stack;
  }


  public static boolean pushSemicolonRuleDisable(PsiBuilder builder, int level) {
    getFlagStack(builder, SEMICOLON_RULE_STATE).push(Boolean.FALSE);
   return true;
  }
  public static boolean pushSemicolonRuleEnable(PsiBuilder builder, int level) {
    getFlagStack(builder, SEMICOLON_RULE_STATE).push(Boolean.TRUE);
   return true;
  }

  public static boolean isSemicolonRuleEnabled(PsiBuilder builder, int level) {
    Stack<Boolean> stack = getFlagStack(builder, SEMICOLON_RULE_STATE);
    if (stack.isEmpty())  return true;
    Boolean peeked = stack.peek();
    if (peeked != null) {
      return peeked;
    }
    return true;
  }


  public static boolean pushCollectionInitializersDeny(PsiBuilder builder, int level) {
    getFlagStack(builder, COLLECTION_INITIALIZER_STATE).push(Boolean.FALSE);
   return true;
  }
  public static boolean pushCollectionInitializersAllow(PsiBuilder builder, int level) {
    getFlagStack(builder, COLLECTION_INITIALIZER_STATE).push(Boolean.TRUE);
   return true;
  }

  public static boolean startWithLowercaseOrUnderscoreCheck(PsiBuilder builder_, int level_) {
    String text = builder_.getTokenText();
    if (text != null) {
      char c = text.charAt(0);
      if (c == '_') return true;
      return Character.isLowerCase(c);
    }
    return false;
  }
  public static boolean startWithUppercaseCheck(PsiBuilder builder_, int level_) {
    String text = builder_.getTokenText();
    if (text != null) {
      char c = text.charAt(0);
      return Character.isUpperCase(c);
    }
    return false;
  }


  public static boolean isInitializersAllowed(PsiBuilder builder, int level) {
    Stack<Boolean> stack = getFlagStack(builder, COLLECTION_INITIALIZER_STATE);
    if (stack.isEmpty())  return true;
    Boolean peeked = stack.peek();
    if (peeked != null) {
      return peeked;
    }
   return true;
  }

  public static final Hook<Boolean> POP_COLLECTION_INITIALIZERS_RULE =
    (builder, marker, param) -> {
      if (builder != null) {
        Stack<Boolean> stack = getFlagStack(builder, COLLECTION_INITIALIZER_STATE);
        if(!stack.isEmpty()) stack.pop();
        }
      return marker;
    };

  public static final Hook<Boolean> POP_SEMICOLON_RULE =
    (builder, marker, param) -> {
      Stack<Boolean> stack = getFlagStack(builder, SEMICOLON_RULE_STATE);
      if(!stack.isEmpty()) stack.pop();
      return marker;
    };



}