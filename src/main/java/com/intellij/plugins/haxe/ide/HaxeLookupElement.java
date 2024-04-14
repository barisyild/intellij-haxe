/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
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
package com.intellij.plugins.haxe.ide;

import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.JavaCompletionUtil;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.*;
import com.intellij.plugins.haxe.model.type.HaxeGenericResolver;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.intellij.plugins.haxe.metadata.psi.HaxeMeta.NO_COMPLETION;
import static com.intellij.plugins.haxe.model.type.HaxeGenericResolverUtil.getResolverSkipAbstractNullScope;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeLookupElement extends LookupElement {
  private final HaxeComponentName myComponentName;
  private final HaxeResolveResult leftReference;
  private final HaxeMethodContext context;

  private final HaxeGenericResolver resolver;
  private HaxeBaseMemberModel model;

  private String presentableText;
  private String tailText;
  private boolean strikeout = false;
  private boolean bold = false;
  private Icon icon = null;

  public static Collection<HaxeLookupElement> convert(HaxeResolveResult leftReferenceResolveResult,
                                                      @NotNull Collection<HaxeComponentName> componentNames,
                                                      @NotNull Collection<HaxeComponentName> componentNamesExtension,
                                                      HaxeGenericResolver resolver) {
    final List<HaxeLookupElement> result = new ArrayList<>(componentNames.size());
    for (HaxeComponentName componentName : componentNames) {
      HaxeMethodContext context = null;
      boolean shouldBeIgnored = false;
      if (componentNamesExtension.contains(componentName)) {
        context = HaxeMethodContext.EXTENSION;
      } else {
        context = HaxeMethodContext.NO_EXTENSION;
      }

      // TODO figure out if  @:noUsing / NO_USING should be filtered

      if(componentName.getParent() instanceof HaxeFieldDeclaration fieldDeclaration) {
        shouldBeIgnored = fieldDeclaration.hasCompileTimeMetadata(NO_COMPLETION) ;
      }
      if(componentName.getParent() instanceof HaxeMethodDeclaration methodDeclaration) {
        shouldBeIgnored = methodDeclaration.hasCompileTimeMetadata(NO_COMPLETION) ;
      }
      if (!shouldBeIgnored) {
        result.add(new HaxeLookupElement(leftReferenceResolveResult, componentName, context, resolver));
      }
    }
    return result;
  }

  public HaxeLookupElement(HaxeResolveResult leftReference, HaxeComponentName name, HaxeMethodContext context, HaxeGenericResolver resolver) {
    this.leftReference = leftReference;
    this.myComponentName = name;
    this.context = context;
    this.resolver = resolver;


    calculatePresentation();
  }

  @NotNull
  @Override
  public String getLookupString() {
    return myComponentName.getIdentifier().getText();
  }

  @Override
  public void renderElement(LookupElementPresentation presentation) {
    presentation.setItemText(presentableText);
    presentation.setStrikeout(strikeout);
    presentation.setItemTextBold(bold);
    presentation.setIcon(icon);
    presentation.setTailText(tailText, true);

  }

  public void calculatePresentation() {
    final ItemPresentation myComponentNamePresentation = myComponentName.getPresentation();
    if (myComponentNamePresentation == null) {
      presentableText = getLookupString();
      return;
    }

    model = HaxeBaseMemberModel.fromPsi(myComponentName);
    if (model == null) {
      presentableText = myComponentNamePresentation.getPresentableText();
    }else {
      presentableText = model.getPresentableText(context, resolver);
      // Check deprecated modifiers
      if (model instanceof HaxeMemberModel && ((HaxeMemberModel)model).getModifiers().hasModifier(HaxePsiModifier.DEPRECATED)) {
        strikeout = true;
      }

      // Check for non-inherited members to highlight them as intellij-java does
      if (leftReference != null) {
        HaxeClassModel declaringClass = model.getDeclaringClass();
        if (declaringClass!= null && declaringClass.getPsi() == leftReference.getHaxeClass()) {
          bold = true;
        }
      }
    }

    icon = myComponentNamePresentation.getIcon(true);
    final String pkg = myComponentNamePresentation.getLocationString();
    if (StringUtil.isNotEmpty(pkg)) {
      tailText =" " + pkg;
    }
  }

  @Override
  public void handleInsert(InsertionContext context) {
    HaxeBaseMemberModel memberModel = HaxeBaseMemberModel.fromPsi(myComponentName);
    boolean hasParams = false;
    boolean isMethod = false;
    if (memberModel != null) {
      if (memberModel instanceof HaxeMethodModel) {
        isMethod = true;
        HaxeMethodModel methodModel = (HaxeMethodModel)memberModel;
        hasParams = !methodModel.getParametersWithContext(this.context).isEmpty();
      }
    }

    if (isMethod) {
      final LookupElement[] allItems = context.getElements();
      final boolean overloadsMatter = allItems.length == 1 && getUserData(JavaCompletionUtil.FORCE_SHOW_SIGNATURE_ATTR) == null;
      JavaCompletionUtil.insertParentheses(context, this, overloadsMatter, hasParams);
    }
  }



  @NotNull
  @Override
  public Object getObject() {
    return myComponentName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof HaxeLookupElement)) return false;

    return myComponentName.equals(((HaxeLookupElement)o).myComponentName);
  }

  @Override
  public int hashCode() {
    return myComponentName.hashCode();
  }
}
