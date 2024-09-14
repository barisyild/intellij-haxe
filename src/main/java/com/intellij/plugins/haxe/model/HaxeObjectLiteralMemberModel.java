package com.intellij.plugins.haxe.model;

import com.intellij.plugins.haxe.lang.psi.HaxeComponentName;
import com.intellij.plugins.haxe.lang.psi.HaxeObjectLiteral;
import com.intellij.plugins.haxe.lang.psi.HaxeObjectLiteralElement;
import com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluator;
import com.intellij.plugins.haxe.model.type.*;
import org.jetbrains.annotations.Nullable;

public class HaxeObjectLiteralMemberModel extends HaxeBaseMemberModel {
  private final HaxeObjectLiteralElement myPsi;

  public HaxeObjectLiteralMemberModel(HaxeObjectLiteralElement basePsi) {
    super(basePsi);
    myPsi = basePsi;
  }


  @Override
  public HaxeComponentName getNamePsi() {
    return myPsi.getComponentName();
  }

  @Override
  public @Nullable HaxeClassModel getDeclaringClass() {
    if (myPsi.getParent() instanceof HaxeObjectLiteral objectLiteral) {
      return objectLiteral.getModel();
    }
    return null;
  }

  @Override
  public HaxeModuleModel getDeclaringModule() {
    return null;
  }

  @Override
  public @Nullable HaxeExposableModel getExhibitor() {
    return getDeclaringClass();
  }

  @Override
  public @Nullable FullyQualifiedInfo getQualifiedInfo() {
    return null; // object literals are unnamed so no FullyQualifiedInfo available
  }


  @Override
  public ResultHolder getResultType(@Nullable HaxeGenericResolver resolver) {
    return HaxeExpressionEvaluator.evaluate(myPsi.getExpression(), resolver).result;
  }
}
