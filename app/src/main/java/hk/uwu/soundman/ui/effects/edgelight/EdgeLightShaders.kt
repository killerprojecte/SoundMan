package hk.uwu.soundman.ui.effects.edgelight

import org.intellij.lang.annotations.Language

@Language("AGSL")
internal const val RoundedRectSDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

@Language("AGSL")
internal const val EdgeLightShaderString = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float width;
uniform float blurRadius;
uniform float intensity;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    
    float edgeDist = abs(sd);
    
    float halfWidth = width * 0.5;
    float innerEdge = halfWidth;
    float outerEdge = halfWidth + blurRadius;
    
    float alpha = 1.0 - smoothstep(innerEdge, outerEdge, edgeDist);
    
    alpha *= intensity;
    
    return color * alpha;
}"""

@Language("AGSL")
internal const val EdgeLightDirectionalShaderString = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float width;
uniform float blurRadius;
uniform float intensity;
uniform float angle;
uniform float falloff;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float edgeDist = abs(sd);
    
    float halfWidth = width * 0.5;
    float innerEdge = halfWidth;
    float outerEdge = halfWidth + blurRadius;
    
    float edgeAlpha = 1.0 - smoothstep(innerEdge, outerEdge, edgeDist);
    
    float directionalAlpha = pow(abs(d), falloff);
    
    float alpha = edgeAlpha * directionalAlpha * intensity;
    
    return color * alpha;
}"""

@Language("AGSL")
internal const val EdgeLightGlowShaderString = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float width;
uniform float blurRadius;
uniform float intensity;
uniform float glowSize;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    
    float edgeDist = abs(sd);
    
    float halfWidth = width * 0.5;
    float coreAlpha = 1.0 - smoothstep(halfWidth * 0.5, halfWidth, edgeDist);
    
    float glowAlpha = exp(-edgeDist * edgeDist / (2.0 * glowSize * glowSize));
    
    float alpha = (coreAlpha + glowAlpha * 0.5) * intensity;
    
    return color * alpha;
}"""
