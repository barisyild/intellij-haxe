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
package com.intellij.plugins.haxe.ide.inspections;

import com.intellij.codeInspection.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.ide.annotator.HaxeAnnotatingVisitor;
import com.intellij.plugins.haxe.ide.inspections.intentions.HaxeIntroduceFieldIntention;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.intellij.plugins.haxe.ide.inspections.HaxeUnresolvedSymbolQuickFixes.*;

/**
 * Created by fedorkorotkov.
 */
public class HaxeUnresolvedSymbolInspection extends LocalInspectionTool {
  @NotNull
  public String getGroupDisplayName() {
    return HaxeBundle.message("inspections.group.name");
  }

  @Nls
  @NotNull
  @Override
  public String getDisplayName() {
    return HaxeBundle.message("haxe.inspection.unresolved.symbol");
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @NotNull
  @Override
  public String getShortName() {
    return "HaxeUnresolvedSymbol";
  }

  @Nullable
  @Override
  public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull final InspectionManager manager, final boolean isOnTheFly) {
    if (!(file instanceof HaxeFile)) return null;
    final List<ProblemDescriptor> result = new ArrayList<ProblemDescriptor>();
    new HaxeAnnotatingVisitor() {
      @Override
      protected void handleUnresolvedReference(HaxeReferenceExpression reference) {
        PsiElement nameIdentifier = reference.getReferenceNameElement();
        if (nameIdentifier == null) return;
        if (isPartOfImportStatement(reference)) {
          result.add(manager.createProblemDescriptor(
            nameIdentifier,
            TextRange.from(0, nameIdentifier.getTextLength()),
            getDisplayName(),
            ProblemHighlightType.ERROR,
            isOnTheFly
          ));
        }

        PsiElement element = nameIdentifier;
        TextRange from = TextRange.from(0, element.getTextLength());
        if (reference.getParent() instanceof HaxeCallExpression callExpression) {
          //"expand" so quickfix covers entire call expression
          element = callExpression;
          HaxeExpression expression = callExpression.getExpression();
          if (expression == null) return;
          @NotNull PsiElement[] children = expression.getChildren();
          PsiElement child = children[children.length - 1];
          TextRange rangeInParent = child.getTextRangeInParent();
          int offset = rangeInParent.getStartOffset();
          from = TextRange.from(offset, callExpression.getTextLength() - offset);
        }


        result.add(manager.createProblemDescriptor(
          element,
          from,
          getDisplayName(),
          ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
          isOnTheFly,
          createQuickfixesIfAvailable(reference)
        ));
      }
    }.visitFile(file);
    return ArrayUtil.toObjectArray(result, ProblemDescriptor.class);
  }

  private LocalQuickFix[] createQuickfixesIfAvailable(HaxeReferenceExpression reference) {
    List<LocalQuickFix> list = new ArrayList<>();
    HaxeClass targetClass = HaxeIntroduceFieldIntention.getTargetClass(reference);
    if (reference.getParent() instanceof HaxeCallExpression callExpression) {
      HaxeExpression expression = callExpression.getExpression();
      if (expression != null) {
        if (expression.getChildren().length == 1) {
          list.add(createFunctionQuickfix(callExpression));
        }
        if (targetClass instanceof HaxeClassDeclaration || targetClass instanceof HaxeExternClassDeclaration) {
          list.add(createMethodQuickfix(callExpression, targetClass));
        }
      }
    }else {
      @NotNull PsiElement[] children = reference.getChildren();
      if (children.length  == 1) { // references is "local"
        list.add(createLocalVarQuickfix(reference));
        list.add(createMethodParameterQuickfix(reference));
      }
      if (targetClass instanceof HaxeClassDeclaration || targetClass instanceof HaxeExternClassDeclaration) {
        list.add(createFieldQuickfix(reference, targetClass));
      }
    }
    return list.stream().filter(Objects::nonNull).toArray(LocalQuickFix[]::new);
  }


  private boolean isPartOfImportStatement(HaxeReferenceExpression reference) {
    PsiElement parent = reference.getParent();
    while (parent instanceof HaxeReferenceExpression) {
      parent = parent.getParent();
    }
    return parent instanceof HaxeImportStatement;
  }
}
