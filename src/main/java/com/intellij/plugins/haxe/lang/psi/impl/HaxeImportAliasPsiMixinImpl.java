package com.intellij.plugins.haxe.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.model.type.SpecificHaxeClassReference;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;

public class HaxeImportAliasPsiMixinImpl extends HaxeStatementPsiMixinImpl implements HaxeImportAlias {
  public HaxeImportAliasPsiMixinImpl(ASTNode node) {
    super(node);
  }


  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof HaxeVisitor) {
      ((HaxeVisitor)visitor).visitImportAlias(this);
    }
    else {
      super.accept(visitor);
    }
  }

  @Override
  public @NotNull HaxeIdentifier getIdentifier() {
    return findChildByClass(HaxeIdentifier.class);
  }

  @Override
  public @NotNull HaxeResolveResult resolveHaxeClass() {
    if(getParent() instanceof  HaxeImportStatement importStatement) {
      HaxeReferenceExpression expression = importStatement.getReferenceExpression();
      if (expression != null) {
        HaxeResolveResult result = expression.resolveHaxeClass();
        HaxeClass haxeClass = result.getHaxeClass();
        if (haxeClass != null) {
          HaxeClassModel model = haxeClass.getModel();
          @NotNull ResultHolder[] specifics = result.getGenericResolver().getSpecificsFor(haxeClass);
          SpecificHaxeClassReference reference = SpecificHaxeClassReference.withGenerics(model.getReference(), specifics);
          // TODO mlo: figure out why we can get both Class<T> and T from resolveHaxeClass
          if (reference.isClass() && specifics.length == 1) {
            // extract type from Class<T>
            return specifics[0].getClassType().asResolveResult();
          }else {
            return result;
          }
        }
      }
    }
    return HaxeResolveResult.EMPTY;
  }

}
