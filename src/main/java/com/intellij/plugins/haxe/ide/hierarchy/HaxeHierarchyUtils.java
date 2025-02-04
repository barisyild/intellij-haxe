/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018-2019 Eric Bishton
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
package com.intellij.plugins.haxe.ide.hierarchy;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.TargetElementUtilBase;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.lang.psi.impl.AbstractHaxePsiClass;
import com.intellij.plugins.haxe.lang.psi.impl.AnonymousHaxeTypeImpl;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.CustomLog;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ebishton on 9/4/14.
 *
 * A set of utility functions that support the HierarchyProviders.
 */
@CustomLog
public class HaxeHierarchyUtils {

  //static
  //{
  //  LOG.setLevel(Level.DEBUG);
  //}

  private HaxeHierarchyUtils() {
    throw new NotImplementedException("Static use only.");
  }


  /**
   * Given a PSI id element, find out if it -- or one of its parents --
   * references a class, and, if so, returns the PSI element for the class.
   *
   * @param id A PSI element for an identifier (e.g. variable name).
   * @return A PSI class element, or null if not found.
   */
  @Nullable
  public static HaxeClass findReferencedClassForId(@NotNull LeafPsiElement id) {
    if (null == id) {
      return null;
    }

    PsiReference found = id.findReferenceAt(0);
    PsiElement resolved = null;
    if (found instanceof PsiMultiReference) {
      for (PsiReference ref : ((PsiMultiReference)found).getReferences()) {
        PsiElement target = ref.resolve();
        if (null != target && target instanceof PsiClass) {
          resolved = target;
          break;
        }
      }
    }
    else {
      resolved = found.resolve();
    }

    if (log.isDebugEnabled()) {
      log.debug("findReferencedClassForID found " + resolved);
    }

    return ((resolved instanceof HaxeClass) ? ((HaxeClass) resolved) : null);
  }

  /**
   * Retrieve the list of classes implemented in the given File.
   *
   * @param psiRoot - File to search.
   * @return An array of found classes, or an empty array if none.
   */
  public static HaxeClass[] getClassArray(@NotNull HaxeFile psiRoot) {
    List<HaxeClass> list = getClassList(psiRoot);
    HaxeClass[] targetArray = new HaxeClass[list.size()];
    return (list.toArray(targetArray));
  }

  /**
   * Retrieve the list of classes implemented in the given File.
   *
   * @param psiRoot - File to search.
   * @return A List of found classes, or an empty array if none.
   */
  @NotNull
  public static List<HaxeClass> getClassList(@NotNull HaxeFile psiRoot) {
    return  CachedValuesManager.getCachedValue(psiRoot, () -> {
      ArrayList<HaxeClass> classes = new ArrayList<>();
      @NotNull PsiElement[] children = psiRoot.getModel().getModuleBodyChildren();
      for (PsiElement child : children) {
        if (child instanceof HaxeClass haxeClass) {
          classes.add(haxeClass);
        }
      }
      return new CachedValueProvider.Result<>(classes, psiRoot);
    });

  }

  /**
   * Get the PSI element for the class containing the currently focused
   * element.  Anonymous classes can be excluded if desired.
   *
   * @param context - editing context
   * @param allowAnonymous - flag to allow anonymous classes or not.
   * @return The PSI element representing the containing class.
   */
  @Nullable
  public static AbstractHaxePsiClass getContainingClass(@NotNull DataContext context, boolean allowAnonymous) {
    if (log.isDebugEnabled()) {
      log.debug("getContainingClass " + context);
    }

    final Project project = CommonDataKeys.PROJECT.getData(context);
    if (project == null) {
      if (log.isDebugEnabled()) {
        log.debug("No project");
      }
      return null;
    }

    final Editor editor = CommonDataKeys.EDITOR.getData(context);
    if (log.isDebugEnabled()) {
      log.debug("editor " + editor);
    }
    if (editor != null) {
      final PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      if (file == null) {
        if (log.isDebugEnabled()) {
          log.debug("No file found.");
        }
        return null;
      }

      final PsiElement targetElement = TargetElementUtil.findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED |
                                                                                   TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED |
                                                                                   TargetElementUtil.LOOKUP_ITEM_ACCEPTED);
      if (log.isDebugEnabled()) {
        log.debug("target element " + targetElement);
      }
      if (targetElement instanceof AbstractHaxePsiClass) {
        return (AbstractHaxePsiClass)targetElement;
      }

      // Haven't found it yet, walk the PSI tree toward the root.
      final int offset = editor.getCaretModel().getOffset();
      PsiElement element = file.findElementAt(offset);
      while (element != null) {
        if (log.isDebugEnabled()) {
          log.debug("context element " + element);
        }
        if (element instanceof HaxeFile) {
          // If we get to the file node, then we're outside of a class definition.
          // No need to look further.
          return null;
        }
        if (element instanceof AbstractHaxePsiClass) {
          // Keep looking if we don't allow anonymous classes.
          if (allowAnonymous || !(element instanceof AnonymousHaxeTypeImpl)) {
            return (AbstractHaxePsiClass)element;
          }
        }
        element = element.getParent();
      }

      return null;
    }
    else {
      final PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(context);
      return element instanceof AbstractHaxePsiClass ? (AbstractHaxePsiClass)element : null;
    }
  }

  /**
   * Retrieve the PSI element for the file containing the given
   * context (focus element).
   *
   * @param context - editing context
   * @return The PSI node representing the file element.
   */
  @Nullable
  public static HaxeFile getContainingFile(@NotNull DataContext context) {
    if (log.isDebugEnabled()) {
      log.debug("getContainingFile " + context);
    }

    // XXX: EMB: Can we just ask for the node at offset 0??
    PsiElement element = getPsiElement(context);
    while (element != null) {
      if (element instanceof HaxeFile) {
        return (HaxeFile)element;
      }
      element = element.getParent();
    }
    return null;
  }

  /**
   * Retrieve the PSI element for the given context (focal point).
   * Returns the leaf-node element at the exact position in the PSI.
   * This does NOT attempt to locate a higher-order PSI element as
   * {@link TargetElementUtilBase#findTargetElement} would.
   *
   * @param context - editing context
   * @return The PSI element at the caret position.
   */
  @Nullable
  public static PsiElement getPsiElement(@NotNull DataContext context) {
    if (log.isDebugEnabled()) {
      log.debug("getPsiElement " + context);
    }

    PsiElement element = null;

    final Project project = CommonDataKeys.PROJECT.getData(context);
    if (project == null) {
      if (log.isDebugEnabled()) {
        log.debug("No project");
      }
      return null;
    }

    final Editor editor = CommonDataKeys.EDITOR.getData(context);
    if (log.isDebugEnabled()) {
      log.debug("editor " + editor);
    }
    if (editor != null) {
      final PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      if (file == null) {
        if (log.isDebugEnabled()) {
          log.debug("No file found.");
        }
        return null;
      }

      final int offset = editor.getCaretModel().getOffset();
      element = file.findElementAt(offset);
    }
    else {
      element = CommonDataKeys.PSI_ELEMENT.getData(context);
    }
    return element;
  }

  @Nullable
  public static PsiElement getReferencedElement(@NotNull DataContext context) {
    PsiElement element = null;

    final Editor editor = CommonDataKeys.EDITOR.getData(context);
    if (editor != null) {
      element = TargetElementUtil.findTargetElement(editor,
                                    TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED |
                                    TargetElementUtil.ELEMENT_NAME_ACCEPTED);
    }

    return element;
  }

  /**
   * Determine if there is a method that is the target of the current
   * action, and, if so, return it.
   *
   * @param context Editor context.
   * @return The PSI method if the current context points at a method,
   *         null otherwise.
   */
  @Nullable
  public static HaxeMethod getTargetMethod(@NotNull DataContext context) {

    if (log.isDebugEnabled()) {
      log.debug("getTargetMethod " + context);
    }

    final Project project = CommonDataKeys.PROJECT.getData(context);
    if (null == project) {
      if (log.isDebugEnabled()) {
        log.debug("No project");
      }
      return null;
    }

    final Editor editor = CommonDataKeys.EDITOR.getData(context);

    if (null == editor) {
      if (log.isDebugEnabled()) {
        log.debug("No editor");
      }

      final PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(context);
      return element instanceof HaxeMethod ? (HaxeMethod) element : null;
    }

    if (log.isDebugEnabled()) {
      log.debug("editor " + editor);
    }

    final PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    if (file == null) {
      if (log.isDebugEnabled()) {
        log.debug("No file found.");
      }
      return null;
    }

    final PsiElement targetElement = TargetElementUtil.findTargetElement(editor,
                                                                             TargetElementUtil.ELEMENT_NAME_ACCEPTED |
                                                                             TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED |
                                                                             TargetElementUtil.LOOKUP_ITEM_ACCEPTED);
    if (log.isDebugEnabled()) {
      log.debug("target element " + targetElement);
    }

    if (targetElement instanceof HaxeMethod) {
      log.debug("target element " + targetElement);
      return ((HaxeMethod) targetElement);
    }

    // Haven't found it yet, walk the PSI tree toward the root.
    final int offset = editor.getCaretModel().getOffset();
    PsiElement element = file.findElementAt(offset);
    while (element != null) {
      if (log.isDebugEnabled()) {
        log.debug("context element " + element);
      }
      if (element instanceof HaxeFile) {
        // If we get to the file node, then we're outside of a class definition.
        // No need to look further.
        return null;
      }
      if (element instanceof HaxeMethod) {
          return ((HaxeMethod)element);
      }
      element = element.getParent();
    }

    return null;
  }


  /**
   * Determine the class (PSI element), if any, that is referenced by the
   * given reference expression.
   *
   * @param element A PSI reference expression.
   * @return The associated class, if any.  null if not found.
   */
  @Nullable
  public static HaxeClass resolveClassReference(@NotNull HaxeReference element) {
    HaxeResolveResult result = element.resolveHaxeClass();
    return result.getHaxeClass();
  }



  // Lifted from MethodHierarchyUtil, which needed the cannotBeOverriding helper
  // to be overridden.
  /**
   * Locates a potentially overridden method in a sub-class or an
   * intermediate parent -- up to the class containing the base method.
   *
   * Note: This CANNOT be used to find a method in a super-class of
   * baseMethod's class. Only classes that are sub-classes of baseMethod are
   * considered.
   *
   * @param baseMethod The method that may be overridden
   * @param aClass The sub-class to start inspecting at.
   * @param checkBases Whether to continue to further base classes.
   * @return The PsiMethod in the given class or the closest superclass.
   */
  public static PsiMethod findBaseMethodInClass(final PsiMethod baseMethod, final PsiClass aClass, final boolean checkBases) {
    if (baseMethod == null) return null; // base method is invalid
    if (cannotBeOverriding(baseMethod)) return null;
    return MethodSignatureUtil.findMethodBySuperMethod(aClass, baseMethod, checkBases);
  }

  /**
   * Figure out if a method can override a lower one.
   * @param method The method to test.
   * @return true if the method can override one in a superclass, false if not.
   */
  public static boolean cannotBeOverriding(final PsiMethod method) {
    // Note that in Haxe, a private method can override another private
    // method, and so can a public method (but private can't override public).
    final PsiClass parentClass = method.getContainingClass();
    return parentClass == null
           || method.isConstructor()
           || method.hasModifierProperty(PsiModifier.STATIC);
  }

  public static List<HaxeComponentName> findMembersByWalkingTree(@NotNull PsiElement element) {
    List<HaxeComponentName> members = new ArrayList<>();
    CollectMembersScopeProcessor processor = new CollectMembersScopeProcessor(members);
    PsiTreeUtil.treeWalkUp(processor, element, element.getContainingFile(), new ResolveState());
    return members;
  }

  private static class CollectMembersScopeProcessor implements PsiScopeProcessor {
    private final List<HaxeComponentName> result;

    private CollectMembersScopeProcessor(List<HaxeComponentName> result) {
      this.result = result;
    }

    @Override
    public boolean execute(@NotNull PsiElement element, ResolveState state) {
      addNormalEmbers(element);
      addEnumObjectLiterals(element);
      addMembersFromSwitchCase(element);
      return true;
    }

    private void addEnumObjectLiterals(@NotNull PsiElement element) {
      if (element.getParent() instanceof HaxeEnumObjectLiteralElement) {
        //TODO
        //result.add(element);
      }
    }

    private void addNormalEmbers(@NotNull PsiElement element) {
      HaxeComponentName componentName = null;
      if (element instanceof HaxeComponentName haxeComponentName ) {
        componentName = haxeComponentName;
      }
      else if (element instanceof HaxeNamedComponent namedComponent) {
        componentName = namedComponent.getComponentName();
      }
      else if (element instanceof HaxeOpenParameterList parameterList) {
        componentName = parameterList.getUntypedParameter().getComponentName();

      }
      if(componentName != null) result.add(componentName);
    }

    private void addMembersFromSwitchCase(PsiElement element) {
      if (element instanceof HaxeSwitchCaseExpr expr) {
        if (expr.getSwitchCaseCaptureVar() != null) {
          HaxeComponentName componentName = expr.getSwitchCaseCaptureVar().getComponentName();
          result.add(componentName);
        }
        else {
          HaxeExpression expression = expr.getExpression();
          if (expression instanceof HaxeEnumArgumentExtractor extractor) {
            HaxeEnumExtractorArgumentList argumentList = extractor.getEnumExtractorArgumentList();

            List<HaxeEnumExtractedValue> list = argumentList.getEnumExtractedValueList();
            for (HaxeEnumExtractedValue extractedValue : list) {
              HaxeEnumExtractedValueReference valueReference = extractedValue.getEnumExtractedValueReference();
              if (valueReference != null) {
                HaxeComponentName componentName = valueReference.getComponentName();
                result.add(componentName);
              }
            }
          }
        }
      }
    }
  }
} // END class HaxeHierarchyUtils
