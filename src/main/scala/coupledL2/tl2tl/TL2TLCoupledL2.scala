/** *************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  *          http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  * *************************************************************************************
  */

package coupledL2.tl2tl

import org.chipsalliance.cde.config.{Parameters, Field}
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import coupledL2._

class TL2TLCoupledL2(implicit p: Parameters) extends CoupledL2Base {

  class CoupledL2Imp(wrapper: LazyModule) extends BaseCoupledL2Imp(wrapper)

  lazy val module = new CoupledL2Imp(this)
}