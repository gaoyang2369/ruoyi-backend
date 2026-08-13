package org.ruoyi.service.fault;

import org.ruoyi.common.core.utils.StringUtils;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 将用户或模型给出的常见设备别名收敛为配置中的标准资产名。
 * <p>
 * 这里仅处理可确定的字符等价（例如“电机一”与“电机1”）。语义或读音相近的表达
 * 仍由请求规划模型结合允许资产列表判断，避免模糊匹配误选设备。
 */
final class FaultAssetNameResolver {
    private FaultAssetNameResolver() {
    }

    /** 在一段用户文本中查找出现的允许资产，优先返回名称更长的资产。 */
    static Optional<String> findMentionedAsset(String text, List<String> allowedAssets) {
        if (StringUtils.isBlank(text) || allowedAssets == null) return Optional.empty();
        String normalizedText = normalize(text);
        return allowedAssets.stream()
            .filter(StringUtils::isNotBlank)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .filter(asset -> normalizedText.contains(normalize(asset)))
            .findFirst();
    }

    /** 若名称是允许资产的确定性别名，则返回配置中的标准名称；否则保持原值。 */
    static String canonicalize(String deviceName, List<String> allowedAssets) {
        if (StringUtils.isBlank(deviceName) || allowedAssets == null) return deviceName;
        String normalizedName = normalize(deviceName);
        return allowedAssets.stream()
            .filter(StringUtils::isNotBlank)
            .filter(asset -> normalize(asset).equals(normalizedName))
            .findFirst()
            .orElse(deviceName);
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("[\\s\\p{Punct}，。、】【、】【（）()]+", "")
            .toLowerCase(java.util.Locale.ROOT);
        return normalized
            .replace('零', '0').replace('〇', '0')
            .replace('一', '1').replace('二', '2').replace('三', '3').replace('四', '4')
            .replace('五', '5').replace('六', '6').replace('七', '7').replace('八', '8').replace('九', '9');
    }
}
