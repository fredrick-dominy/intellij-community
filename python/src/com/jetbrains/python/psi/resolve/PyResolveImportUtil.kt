/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

@file:JvmName("PyResolveImportUtil")
package com.jetbrains.python.psi.resolve

import com.google.common.base.Preconditions
import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.FileIndexFacade
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.QualifiedName
import com.jetbrains.python.codeInsight.typing.PyTypeShed
import com.jetbrains.python.codeInsight.userSkeletons.PyUserSkeletonsUtil
import com.jetbrains.python.facet.PythonPathContributingFacet
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyUtil
import com.jetbrains.python.psi.impl.PyBuiltinCache
import com.jetbrains.python.psi.impl.PyImportResolver
import com.jetbrains.python.pyi.PyiFile
import com.jetbrains.python.sdk.PySdkUtil
import com.jetbrains.python.sdk.PythonSdkType

/**
 * Python resolve utilities for qualified names.
 *
 * TODO: Merge with ResolveImportUtil, maybe make these functions the methods of PyQualifiedNameResolveContext.
 *
 * @author vlan
 */

/**
 * Resolves a qualified [name] a list of modules / top-level elements according to the [context].
 */
fun resolveQualifiedName(name: QualifiedName, context: PyQualifiedNameResolveContext): List<PsiElement> {
  checkAccess()
  if (!context.isValid) {
    return emptyList()
  }

  val relativeDirectory = context.containingDirectory
  val relativeResult = resolveWithRelativeLevel(name, context)
  val foundRelativeImport = relativeDirectory != null && relativeResult != null &&
      isRelativeImportResult(name, relativeDirectory, relativeResult, context)

  val cache = findCache(context)
  val mayCache = cache != null && !context.withoutRoots && !context.withoutForeign && !foundRelativeImport
  val key = if (context.withoutStubs) QualifiedName.fromDottedString("without-stubs.$name") else name

  if (mayCache) {
    val cachedResults = cache?.get(key)
    if (cachedResults != null) {
      return listOf(listOfNotNull(relativeResult), cachedResults).flatten()
    }
  }

  val allResults = listOf(listOfNotNull(relativeResult),
                          resultsFromRoots(name, context),
                          relativeResultsFromSkeletons(name, context),
                          foreignResults(name, context)).flatten()
  val results = if (name.componentCount > 0) findFirstResults(allResults) else allResults

  if (mayCache) {
    cache?.put(key, results)
  }

  return results
}

/**
 * Resolves a [name] to the first module member defined at the top-level.
 */
fun resolveTopLevelMember(name: QualifiedName, context : PyQualifiedNameResolveContext): PsiElement? {
  checkAccess()
  val memberName = name.lastComponent ?: return null
  return resolveQualifiedName(name.removeLastComponent(), context)
      .asSequence()
      .filterIsInstance(PyFile::class.java)
      .flatMap { it.multiResolveName(memberName).asSequence() }
      .map { it.element }
      .firstOrNull()
}

/**
 * Resolves a [name] relative to the specified [directory].
 */
fun resolveModuleAt(name: QualifiedName, directory: PsiDirectory?, context: PyQualifiedNameResolveContext): PsiElement? {
  checkAccess()
  if (directory == null || !directory.isValid) {
    return null
  }
  return name.components.fold(directory as PsiElement?) { seeker, component ->
    if (component == null) null
    // TODO: Switch to multi-resolve in this API
    else ResolveImportUtil.resolveChild(seeker, component, context.footholdFile, !context.withMembers,
                                        !context.withPlainDirectories, context.withoutStubs)
  }
}

/**
 * Creates a [PyQualifiedNameResolveContext] from a [foothold] element.
 */
fun fromFoothold(foothold: PsiElement): PyQualifiedNameResolveContext {
  val module = ModuleUtilCore.findModuleForPsiElement(foothold.containingFile)
  val sdk = module?.let { ModuleRootManager.getInstance(module).sdk }
  return PyQualifiedNameResolveContextImpl(foothold.manager, module, foothold, sdk)
}

/**
 * Creates a [PyQualifiedNameResolveContext] from a [module].
 */
fun fromModule(module: Module): PyQualifiedNameResolveContext =
    PyQualifiedNameResolveContextImpl(PsiManager.getInstance(module.project), module, null, ModuleRootManager.getInstance(module).sdk)

/**
 * Creates a [PyQualifiedNameResolveContext] from an [sdk].
 */
fun fromSdk(project: Project, sdk: Sdk): PyQualifiedNameResolveContext =
    PyQualifiedNameResolveContextImpl(PsiManager.getInstance(project), module = null, foothold = null, sdk = sdk)

private fun foreignResults(name: QualifiedName, context: PyQualifiedNameResolveContext) =
    if (context.withoutForeign)
      emptyList()
    else
      Extensions.getExtensions(PyImportResolver.EP_NAME)
          .asSequence()
          .map { it.resolveImportReference(name, context, !context.withoutRoots) }
          .filterNotNull()
          .toList()

private fun relativeResultsFromSkeletons(name: QualifiedName, context: PyQualifiedNameResolveContext): List<PsiElement> {
  val footholdFile = context.footholdFile
  if (context.withoutRoots && footholdFile != null) {
    val virtualFile = footholdFile.virtualFile
    if (virtualFile == null || FileIndexFacade.getInstance(context.project).isInContent(virtualFile)) {
      return emptyList()
    }
    val containingDirectory = context.containingDirectory
    if (containingDirectory != null) {
      val containingName = QualifiedNameFinder.findCanonicalImportPath(containingDirectory, null)
      if (containingName != null && containingName.componentCount > 0) {
        val absoluteName = containingName.append(name)
        val sdk = PythonSdkType.getSdk(footholdFile) ?: return emptyList()
        val skeletonsVirtualFile = PySdkUtil.findSkeletonsDir(sdk) ?: return emptyList()
        val skeletonsDir = context.psiManager.findDirectory(skeletonsVirtualFile)
        return listOfNotNull(resolveModuleAt(absoluteName, skeletonsDir, context))
      }
    }
  }
  return emptyList()
}

/**
 * Filters the results according to their import priority in sys.path.
 */
private fun findFirstResults(results: List<PsiElement>) =
    if (results.all(::isNamespacePackage))
      results
    else {
      val stubFile = results.firstOrNull { it is PyiFile || PyUtil.turnDirIntoInit(it) is PyiFile }
      if (stubFile != null)
        listOf(stubFile)
      else
        listOfNotNull(results.firstOrNull { !isNamespacePackage(it) })
    }

private fun isNamespacePackage(element: PsiElement): Boolean {
  if (element is PsiDirectory) {
    val level = PyUtil.getLanguageLevelForVirtualFile(element.project, element.virtualFile)
    if (level.isAtLeast(LanguageLevel.PYTHON33)) {
      return PyUtil.turnDirIntoInit(element) == null
    }
  }
  return false
}

private fun resolveWithRelativeLevel(name: QualifiedName, context : PyQualifiedNameResolveContext): PsiElement? {
  val footholdFile = context.footholdFile
  if (context.relativeLevel >= 0 && footholdFile != null && !PyUserSkeletonsUtil.isUnderUserSkeletonsDirectory(footholdFile)) {
    return resolveModuleAt(name, context.containingDirectory, context)
  }
  return null
}

private fun resultsFromRoots(name: QualifiedName, context: PyQualifiedNameResolveContext): List<PsiElement> {
  if (context.withoutRoots) {
    return emptyList()
  }

  val moduleResults = mutableListOf<PsiElement>()
  val sdkResults = mutableListOf<PsiElement>()

  val sdk = context.effectiveSdk
  val module = context.module
  val footholdFile = context.footholdFile

  val visitor = RootVisitor { root, module, sdk, isModuleSource ->
    val results = if (isModuleSource) moduleResults else sdkResults
    if (!root.isValid ||
        root == PyUserSkeletonsUtil.getUserSkeletonsDirectory() ||
        sdk != null && PyTypeShed.isInside(root) && !PyTypeShed.maySearchForStubInRoot(name, root, sdk)) {
      return@RootVisitor true
    }
    val result = resolveInRoot(name, root, context)
    if (result != null) {
      results.add(result)
    }
    if (isAcceptRootAsTopLevelPackage(context) && name.matchesPrefix(QualifiedName.fromDottedString(root.name))) {
      val topLevelResult = resolveInRoot(name, root.parent, context)
      if (topLevelResult != null) {
        results.add(topLevelResult)
      }
    }
    return@RootVisitor true
  }

  when {
    context.visitAllModules -> {
      ModuleManager.getInstance(context.project).modules.forEach {
        RootVisitorHost.visitRoots(it, true, visitor)
      }
      when {
        sdk != null ->
          RootVisitorHost.visitSdkRoots(sdk, visitor)
        footholdFile != null ->
            RootVisitorHost.visitSdkRoots(footholdFile, visitor)
      }
    }
    module != null -> {
      val otherSdk = sdk != context.sdk
      RootVisitorHost.visitRoots(module, otherSdk, visitor)
      if (otherSdk && sdk != null) {
        RootVisitorHost.visitSdkRoots(sdk, visitor)
      }
    }
    footholdFile != null -> {
      RootVisitorHost.visitRoots(footholdFile, visitor)
    }
    sdk != null -> {
      RootVisitorHost.visitSdkRoots(sdk, visitor)
    }
    else -> throw IllegalStateException()
  }
  return moduleResults + sdkResults
}

private fun isAcceptRootAsTopLevelPackage(context: PyQualifiedNameResolveContext): Boolean {
  context.module?.let {
    FacetManager.getInstance(it).allFacets.forEach {
      if (it is PythonPathContributingFacet && it.acceptRootAsTopLevelPackage()) {
        return true
      }
    }
  }
  return false
}

private fun resolveInRoot(name: QualifiedName, root: VirtualFile, context: PyQualifiedNameResolveContext): PsiElement? {
  return if (root.isDirectory) resolveModuleAt(name, context.psiManager.findDirectory(root), context) else null
}

private fun findCache(context: PyQualifiedNameResolveContext): PythonPathCache? {
  return when {
    context.visitAllModules -> null
    context.module != null ->
      if (context.effectiveSdk != context.sdk) null else PythonModulePathCache.getInstance(context.module)
    context.footholdFile != null -> {
      val sdk = PyBuiltinCache.findSdkForNonModuleFile(context.footholdFile)
      if (sdk != null) PythonSdkPathCache.getInstance(context.project, sdk) else null
    }
    else -> null
  }
}

private fun isRelativeImportResult(name: QualifiedName, directory: PsiDirectory, result: PsiElement,
                                   context: PyQualifiedNameResolveContext): Boolean {
  if (context.relativeLevel > 0) {
    return true
  }
  else {
    val py2 = LanguageLevel.forElement(directory).isOlderThan(LanguageLevel.PYTHON30)
    return context.relativeLevel == 0 && py2 && PyUtil.isPackage(directory, false, null) &&
        result is PsiFileSystemItem && name != QualifiedNameFinder.findShortestImportableQName(result)
  }
}

private fun checkAccess() {
  Preconditions.checkState(ApplicationManager.getApplication().isReadAccessAllowed, "This method requires read access")
}
