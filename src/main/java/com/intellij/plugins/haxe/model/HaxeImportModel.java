/*
 * Copyright 2017-2018 Ilya Malanin
 * Copyright 2018-2020 Eric Bishton
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
package com.intellij.plugins.haxe.model;

import com.intellij.plugins.haxe.lang.psi.HaxeImportAlias;
import com.intellij.plugins.haxe.lang.psi.HaxeImportStatement;
import com.intellij.plugins.haxe.lang.psi.HaxeReferenceExpression;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class HaxeImportModel extends HaxeImportableModel {

  public HaxeImportModel(@NotNull HaxeImportStatement importStatement) {
    super(importStatement);
  }

  @NotNull
  @Override
  public HaxeImportStatement getBasePsi() {
    return (HaxeImportStatement)basePsi;
  }

  public boolean hasAlias() {
    return getBasePsi().getAlias() != null;
  }

  public boolean hasWildcard() {
    return getBasePsi().getWildcard() != null;
  }

  @Nullable
  public String getAliasName() {
    HaxeImportAlias alias = getBasePsi().getAlias();
    if (alias == null) return null;
    return alias.getIdentifier().getText();
  }

  public HaxeReferenceExpression getReferenceExpression() {
    return getBasePsi().getReferenceExpression();
  }

  @NotNull
  public List<HaxeModel> getExposedMembersInternal() {
    FullyQualifiedInfo qualifiedInfo = getQualifiedInfo();
    List<HaxeModel> result =  new ArrayList<>();
    if (hasWildcard()) {
      if (qualifiedInfo.memberName != null) return Collections.emptyList();

      if (qualifiedInfo.fileName != null && qualifiedInfo.className == null) {
        qualifiedInfo = new FullyQualifiedInfo(qualifiedInfo.packagePath, qualifiedInfo.fileName, qualifiedInfo.fileName, null);
      }
      List<HaxeModel> items = HaxeProjectModel.fromElement(basePsi).resolve(qualifiedInfo, basePsi.getResolveScope());
      if (items != null && items.size() > 0) {
        result = items.stream()
          .filter(model -> model instanceof HaxeExposableModel)
          .flatMap(model -> ((HaxeExposableModel)model).getExposedMembers().stream())
          .collect(Collectors.toList());
      }
    } else {
      result.addAll(super.getExposedMembersInternal());

      if (hasAlias() && qualifiedInfo.fileName != null && qualifiedInfo.className == null) {
        result.add(new HaxeAliasModel(getBasePsi().getAlias()));
      }
    }

    return exposeEnumValues(result);
  }


  @NotNull
  private List<HaxeModel> exposeEnumValues(@NotNull List<HaxeModel> result) {
    result.addAll(
      result.stream()
        .filter(model -> model instanceof HaxeEnumModel)
        .flatMap(model -> ((HaxeEnumModel)model).getValues().stream())
        .collect(Collectors.toList())
    );

    return result;
  }

  @Override
  @Nullable
  public PsiElement exposeByName(String name) {
    if (name == null) return null;

    if (hasWildcard()) {
      for (HaxeModel exposedMember : getExposedMembers()) {
        if (Objects.equals(exposedMember.getName(), name)) return exposedMember.getBasePsi();
      }
    } else {
      if (getReferenceExpression() != null) {
        FullyQualifiedInfo qualifiedInfo = getQualifiedInfo();

        HaxeModel member = getExposedMember(name);
        if (((equalsToAlias(name) || qualifiedInfo.equalsToNamedPart(name)) && member != null) || member != null) {
          return member.getBasePsi();
        }
      }
    }

    return null;
  }

  /**
   *  In the case of HaxeEnumValueModel elements, there might be multiple with the sname name but in different enums
   *  this method  handles this by returning all matches
   */
  @NotNull
  public List<PsiElement> exposeAllByName(String name) {
    List<PsiElement> results = new ArrayList<>();
    if (name == null) return results;
    if (hasAlias()) {
      if (equalsToAlias(name)) {
        List<HaxeModel> members = getExposedMembers();
        if (getReferenceExpression() != null) {
          for (HaxeModel member : members) {
            if (member.getName().equals(getReferenceExpression().getIdentifier().getText())) {
              if (member instanceof HaxeNamedComponentModel componentModel) {
                return List.of(componentModel.getNamePsi());
              } else {
                return List.of(member.getBasePsi());
              }
            }
          }
        }

        return List.of(getBasePsi().getAlias());
      }
    }else {
      for (HaxeModel exposedMember : getExposedMembers()) {
        if (Objects.equals(exposedMember.getName(), name)) {
          if (exposedMember instanceof HaxeEnumValueModel enumValueModel) {
            results.add(enumValueModel.getNamePsi());
          } else {
            PsiElement element = exposedMember instanceof HaxeNamedComponentModel componentModel
                                 ? componentModel.getNamePsi()
                                 : exposedMember.getBasePsi();
            return List.of(element);
          }
        }
      }
    }
    return results;
  }

  private boolean equalsToAlias(String name) {
    return Objects.equals(getAliasName(), name);
  }

  @Override
  @Nullable
  protected HaxeModel getExposedMember(String name) {
    List<? extends HaxeModel> members = getExposedMembers();
    if (members.isEmpty()) return null;
    if (hasAlias()) {
      return (Objects.equals(getAliasName(), name)) ? members.get(0) : null;
    }

    for(HaxeModel member : members) {
      if(name.equals(member.getName())){
        return member;
      }
    }
    return null;

  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    HaxeImportModel model = (HaxeImportModel)o;
    return getBasePsi().equals(model.getBasePsi());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getBasePsi());
  }
}
