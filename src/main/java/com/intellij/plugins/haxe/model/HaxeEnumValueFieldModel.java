package com.intellij.plugins.haxe.model;

import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;


public class HaxeEnumValueFieldModel extends HaxeFieldModel implements HaxeEnumValueModel {
  private final boolean isAbstractType;


  public HaxeEnumValueFieldModel(@NotNull HaxePsiField declaration) {
    super(declaration);
    HaxeClassModel declaringEnum = getDeclaringEnum();
    isAbstractType = declaringEnum != null && declaringEnum.isAbstractType();
  }

  @Override
  public boolean isStatic() {
    return true;
  }

  @Override
  public boolean isPublic() {
    return !isAbstractType() || !hasModifier(HaxePsiModifier.PRIVATE);
  }

  public boolean isAbstractType() {
    return this.isAbstractType;
  }

  @Nullable
  public HaxeEnumValueDeclarationField getEnumValuePsi() {
    return getBasePsi() instanceof HaxeEnumValueDeclarationField field ? field : null;
  }
  @Nullable
  public HaxePsiField getAbstractEnumValuePsi() {
    return getBasePsi() instanceof HaxePsiField field ? field : null;
  }

  @Nullable
  @Override
  public HaxeExposableModel getExhibitor() {
    return getDeclaringClass();
  }


  @Override
  public ResultHolder getResultType(@Nullable HaxeGenericResolver resolver) {
    PsiClass aClass = getMemberPsi().getContainingClass();
    if (aClass instanceof HaxeClass haxeClass) {

      HaxeClassReference superclassReference = new HaxeClassReference(haxeClass.getModel(), haxeClass);
      if (resolver != null) {
        SpecificHaxeClassReference reference =
          SpecificHaxeClassReference.withGenerics(superclassReference, resolver.getSpecificsFor(haxeClass));

        return reference.createHolder();
      } else {
        SpecificHaxeClassReference reference =
          SpecificHaxeClassReference.withoutGenerics(superclassReference);
        return reference.createHolder();
      }
    }
    return SpecificHaxeClassReference.getUnknown(aClass).createHolder();
  }


  @Override
  public String getPresentableText(HaxeMethodContext context) {
    return getName();
  }

  @Nullable
  public HaxeClassModel getDeclaringEnum() {
    PsiClass aClass = getMemberPsi().getContainingClass();
    if (aClass instanceof HaxeClass haxeClass) {
      return haxeClass.getModel();
    }
    return null;
  }

}
