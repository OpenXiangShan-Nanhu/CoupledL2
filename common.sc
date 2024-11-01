import mill._
import scalalib._

trait CoupledL2Module extends ScalaModule {

  def rocketModule: ScalaModule

  def utilityModule: ScalaModule

  def huancunModule: ScalaModule

  def xsutilsModule: ScalaModule

  override def moduleDeps = super.moduleDeps ++ Seq(rocketModule, utilityModule, huancunModule, xsutilsModule)
}
