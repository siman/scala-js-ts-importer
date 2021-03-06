package scala.tools.scalajs.tsimporter

import Trees.{ TypeRef => TypeRefTree, _ }
import sc._

/** The meat and potatoes: the importer
 *  It reads the TypeScript AST and produces (hopefully) equivalent Scala
 *  code.
 */
class Importer(val output: java.io.PrintWriter) {
  import Importer._

  /** Entry point */
  def apply(declarations: List[DeclTree], outputPackage: String) {
    val packParts = outputPackage.split("\\.").map(Name(_))
    val pack = new PackageSymbol(QualifiedName(packParts:_*))

    for (declaration <- declarations)
      processDecl(pack, declaration)

    new Printer(output).printSymbol(pack)
  }

  private def processDecl(owner: ContainerSymbol, declaration: DeclTree) {
    declaration match {
      case VarDecl(IdentName(name), Some(tpe @ ObjectType(members))) =>
        val sym = owner.getModuleOrCreate(name)
        processMembersDecls(owner, sym, members)

      case TypeDecl(TypeNameName(name), tpe @ ObjectType(members)) =>
        val sym = owner.getClassOrCreate(name)
        processMembersDecls(owner, sym, members)

      case InterfaceDecl(TypeNameName(name), tparams, inheritance, members) =>
        val parents: List[TypeRef] =
          if (inheritance.isEmpty) List(TypeRef.Object)
          else inheritance.map(typeToScala)

        val sym = owner.getClassOrCreate(name)
        sym.parents ++= parents
        for (TypeNameName(tparam) <- tparams)
          sym.tparams += tparam
        processMembersDecls(owner, sym, members)

      case VarDecl(IdentName(name), TypeOrAny(tpe)) =>
        val sym = owner.newField(name)
        sym.tpe = typeToScala(tpe)

      case FunctionDecl(IdentName(name), signature) =>
        processDefDecl(owner, name, signature)

      case _ =>
        owner.members += new CommentSymbol("??? "+declaration)
    }
  }

  private def processMembersDecls(enclosing: ContainerSymbol,
      owner: ContainerSymbol, members: List[MemberTree]) {

    val OwnerName = owner.name.last

    lazy val companionClassRef = {
      val tparams = enclosing.findClass(OwnerName) match {
        case Some(clazz) =>
          clazz.tparams.toList.map(tp => TypeRefTree(TypeNameName(tp), Nil))
        case _ => Nil
      }
      TypeRefTree(TypeNameName(OwnerName), tparams)
    }

    for (member <- members) member match {
      case CallMember(signature) =>
        processDefDecl(owner, Name("apply"), signature)

      case ConstructorMember(sig @ FunSignature(tparamsIgnored, params, Some(resultType)))
      if owner.isInstanceOf[ModuleSymbol] && resultType == companionClassRef =>
        val classSym = enclosing.getClassOrCreate(owner.name.last)
        classSym.isTrait = false
        processDefDecl(classSym, Name.CONSTRUCTOR,
            FunSignature(Nil, params, Some(TypeRefTree(CoreType("void")))))

      case PropertyMember(PropertyNameName(name), opt, tpe) =>
        if (name.name != "prototype") {
          val sym = owner.newField(name)
          sym.tpe = typeToScala(tpe)
        }

      case FunctionMember(PropertyNameName(name), opt, signature) =>
        processDefDecl(owner, name, signature)

      case _ =>
        owner.members += new CommentSymbol("??? "+member)
    }
  }

  private def processDefDecl(owner: ContainerSymbol, name: Name,
      signature: FunSignature) {

    for (sig <- makeAlternatives(signature)) {
      val sym = owner.newMethod(name)

      for (TypeNameName(tparam) <- sig.tparams)
        sym.tparams += tparam

      for (FunParam(IdentName(paramName), opt, TypeOrAny(tpe)) <- sig.params) {
        val paramSym = new ParamSymbol(paramName)
        tpe match {
          case RepeatedType(tpe0) =>
            paramSym.tpe = TypeRef.Repeated(typeToScala(tpe0))
          case _ =>
            paramSym.tpe = typeToScala(tpe)
        }
        sym.params += paramSym
      }

      sym.resultType = typeToScala(signature.resultType.orDynamic, true)
    }
  }

  private def makeAlternativeParamss(
      params: List[FunParam]): List[List[FunParam]] = {
    if (params.isEmpty || !params.last.optional) params :: Nil
    else params :: makeAlternativeParamss(params.init)
  }

  private def makeAlternatives(signature: FunSignature): List[FunSignature] = {
    for (params <- makeAlternativeParamss(signature.params))
      yield FunSignature(signature.tparams, params, signature.resultType)
  }

  private def typeToScala(tpe: TypeTree): TypeRef =
    typeToScala(tpe, false)

  private def typeToScala(tpe: TypeTree, anyAsDynamic: Boolean): TypeRef = {
    tpe match {
      case TypeRefTree(tpe: CoreType, Nil) =>
        coreTypeToScala(tpe, anyAsDynamic)

      case TypeRefTree(TypeNameName(name), targs) =>
        val name1: QualifiedName =
          if (name.name == "Array") QualifiedName.Array
          else name
        TypeRef(name1, targs map typeToScala)

      case ObjectType(members) =>
        // ???
        TypeRef.Any

      case FunctionType(FunSignature(tparams, params, Some(resultType))) =>
        if (!tparams.isEmpty) {
          // Type parameters in function types are not supported
          TypeRef.Function
        } else if (params.exists(_.tpe.exists(_.isInstanceOf[RepeatedType]))) {
          // Repeated params in function types are not supported
          TypeRef.Function
        } else {
          val paramTypes =
            for (FunParam(_, _, TypeOrAny(tpe)) <- params)
              yield typeToScala(tpe)
          val targs = paramTypes :+ typeToScala(resultType)

          TypeRef(QualifiedName.Function(params.size), targs)
        }

      case RepeatedType(underlying) =>
        TypeRef(Name.REPEATED, List(typeToScala(underlying)))

      case _ =>
        // ???
        TypeRef.Any
    }
  }

  private def coreTypeToScala(tpe: CoreType,
      anyAsDynamic: Boolean = false): TypeRef = {

    tpe.name match {
      case "any"     => if (anyAsDynamic) TypeRef.Dynamic else TypeRef.Any
      case "dynamic" => TypeRef.Dynamic
      case "void"    => TypeRef.Unit
      case "number"  => TypeRef.Number
      case "bool"    => TypeRef.Boolean
      case "boolean" => TypeRef.Boolean
      case "string"  => TypeRef.String
    }
  }
}

object Importer {
  private val AnyType = TypeRefTree(CoreType("any"))
  private val DynamicType = TypeRefTree(CoreType("dynamic"))

  private implicit class OptType(val optType: Option[TypeTree]) extends AnyVal {
    @inline def orAny: TypeTree = optType.getOrElse(AnyType)
    @inline def orDynamic: TypeTree = optType.getOrElse(DynamicType)
  }

  private object TypeOrAny {
    @inline def unapply(optType: Option[TypeTree]) = Some(optType.orAny)
  }

  private object IdentName {
    @inline def unapply(ident: Ident) =
      Some(Name(ident.name))
  }

  private object TypeNameName {
    @inline def apply(typeName: Name) =
      TypeName(typeName.name)
    @inline def unapply(typeName: TypeName) =
      Some(Name(typeName.name))
  }

  private object PropertyNameName {
    @inline def unapply(propName: PropertyName) =
      Some(Name(escapeApply(propName.name)))
  }

  private def escapeApply(ident: String) =
    if (ident == "apply") "$apply" else ident
}
