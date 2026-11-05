package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Pure display/state regression, not an Android screenshot or sensor test. */
class MaintenanceUiPolicyTest {
    private val p=MaintenanceUiPolicy
    @Test fun closedIdleEntryIsVisible(){assertFalse(p.hideParameterSource(false,false))}
    @Test fun openIdleEntryIsHidden(){assertTrue(p.hideParameterSource(true,false))}
    @Test fun openingEntryIsHidden(){assertTrue(p.hideParameterSource(true,true))}
    @Test fun closingEntryRemainsHidden(){assertTrue(p.hideParameterSource(false,true))}
    @Test fun closeOnlyRestoresEntryOnLastFrame(){for(i in 100 downTo 1)assertTrue(p.hideParameterSource(false,i>0));assertFalse(p.hideParameterSource(false,false))}
    @Test fun repeatedStateUpdatesCannotRevealOpenEntry(){repeat(100){assertTrue(p.hideParameterSource(true,it%2==0))}}
    @Test fun rapidDirectionChangesKeepSingleHiddenEntry(){for(expanded in listOf(true,false,true,false,true))assertTrue(p.hideParameterSource(expanded,true))}
    @Test fun normalToolRowsHaveTwelveDpGap(){assertEquals(12,p.spaceToolGap(1f))}
    @Test fun largerTextIncreasesGap(){assertEquals(18,p.spaceToolGap(1.5f));assertEquals(24,p.spaceToolGap(2f))}
    @Test fun smallFontDoesNotRemoveGap(){assertEquals(12,p.spaceToolGap(.85f))}
    @Test fun toolGapIsBoundedForVeryLargeFonts(){assertEquals(24,p.spaceToolGap(3f))}
    @Test fun stackedAndHorizontalRowsBothRemainSeparated(){for(font in listOf(.85f,1f,1.15f,1.5f,2f,3f))for(firstHeight in listOf(48,64,104,200)){val nextTop=firstHeight+p.spaceToolGap(font);assertTrue(nextTop-firstHeight>=12)}}
    @Test fun exportHelpExplainsOptionalMetadataAndPrivateLocation(){val t=WorkspaceGuide.topic("export").limits;assertTrue(t.contains("可能含位置"));assertTrue(t.contains("不影响照片保存"));assertTrue(t.contains("可能只保留一部分"))}
    @Test fun userHelpHasNoDeveloperAcceptanceInstructions(){val text=WorkspaceGuide.topics.joinToString{it.overview+it.steps+it.limits};for(s in listOf("本轮","真机验收","实际设备检验","不能冒充","不再单列"))assertFalse(s,text.contains(s))}
    @Test fun deletionWarningIsDirectAndRetainsIrreversibleRisk(){val text=ExternalDeletionConsent.MESSAGE;assertFalse(text.contains("我建议"));for(s in listOf("备份","永久删除","任务取消","系统需要授权"))assertTrue(s,text.contains(s))}
}
