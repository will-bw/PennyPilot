import 'package:flutter/material.dart';

/// Quiet daylight colors, with separate tones for actions and ledger data.
ThemeData pennyTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final scheme = (dark ? const ColorScheme.dark() : const ColorScheme.light())
      .copyWith(
        primary: dark ? const Color(0xFFADC9DF) : const Color(0xFF4D6B85),
        onPrimary: dark ? const Color(0xFF193247) : Colors.white,
        primaryContainer: dark
            ? const Color(0xFF2C4152)
            : const Color(0xFFE7EFF5),
        onPrimaryContainer: dark
            ? const Color(0xFFD9E8F3)
            : const Color(0xFF344F66),
        secondary: dark ? const Color(0xFFE6C1A6) : const Color(0xFF836046),
        onSecondary: dark ? const Color(0xFF352418) : Colors.white,
        secondaryContainer: dark
            ? const Color(0xFF40352D)
            : const Color(0xFFF5E9DD),
        onSecondaryContainer: dark
            ? const Color(0xFFF1DDCD)
            : const Color(0xFF71533D),
        tertiary: dark ? const Color(0xFFB6CFBF) : const Color(0xFF4D725D),
        onTertiary: dark ? const Color(0xFF1F3528) : Colors.white,
        surface: dark ? const Color(0xFF191D21) : const Color(0xFFFCFCFA),
        onSurface: dark ? const Color(0xFFEBEDEB) : const Color(0xFF303940),
        onSurfaceVariant: dark
            ? const Color(0xFFB9C1C7)
            : const Color(0xFF67727B),
        surfaceContainerLowest: dark ? const Color(0xFF14181C) : Colors.white,
        surfaceContainerLow: dark
            ? const Color(0xFF22292F)
            : const Color(0xFFF1F5F7),
        surfaceContainer: dark
            ? const Color(0xFF272E34)
            : const Color(0xFFF0F1EE),
        surfaceContainerHigh: dark
            ? const Color(0xFF30383E)
            : const Color(0xFFE8ECEE),
        surfaceContainerHighest: dark
            ? const Color(0xFF39424A)
            : const Color(0xFFE0E6EA),
        outline: dark ? const Color(0xFF89949D) : const Color(0xFF7A8790),
        outlineVariant: dark
            ? const Color(0xFF3D464E)
            : const Color(0xFFDCE2E5),
        error: dark ? const Color(0xFFF0B2A9) : const Color(0xFFAA463B),
        errorContainer: dark
            ? const Color(0xFF51322E)
            : const Color(0xFFF9E5E0),
        onErrorContainer: dark
            ? const Color(0xFFFFDAD3)
            : const Color(0xFF7D3029),
        surfaceTint: Colors.transparent,
      );
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    brightness: brightness,
  );
  final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(12));
  return base.copyWith(
    scaffoldBackgroundColor: scheme.surface,
    textTheme: base.textTheme.copyWith(
      headlineMedium: base.textTheme.headlineMedium?.copyWith(
        fontSize: 30,
        fontWeight: FontWeight.w600,
        letterSpacing: -.6,
      ),
      titleLarge: base.textTheme.titleLarge?.copyWith(
        fontSize: 22,
        fontWeight: FontWeight.w600,
        letterSpacing: -.3,
      ),
      titleMedium: base.textTheme.titleMedium?.copyWith(
        fontSize: 16,
        fontWeight: FontWeight.w600,
      ),
      bodyLarge: base.textTheme.bodyLarge?.copyWith(height: 1.5),
      bodyMedium: base.textTheme.bodyMedium?.copyWith(height: 1.45),
      labelLarge: base.textTheme.labelLarge?.copyWith(
        fontWeight: FontWeight.w600,
      ),
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleSpacing: 20,
      toolbarHeight: 64,
      titleTextStyle: base.textTheme.titleLarge?.copyWith(
        color: scheme.onSurface,
        fontSize: 23,
        fontWeight: FontWeight.w600,
      ),
    ),
    dividerTheme: DividerThemeData(
      color: scheme.outlineVariant,
      space: 1,
      thickness: .6,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainerLow,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      hintStyle: TextStyle(color: scheme.onSurfaceVariant),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide(color: scheme.primary, width: 1.5),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 50),
        shape: shape,
        elevation: 0,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 48),
        shape: shape,
        side: BorderSide(color: scheme.outlineVariant),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        minimumSize: const Size(48, 48),
        shape: shape,
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: scheme.surface,
      selectedColor: scheme.primaryContainer,
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      labelStyle: base.textTheme.labelLarge?.copyWith(
        color: scheme.onSurfaceVariant,
        fontSize: 13,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 5),
      showCheckmark: false,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      height: 72,
      indicatorColor: Colors.transparent,
      iconTheme: WidgetStateProperty.resolveWith(
        (states) => IconThemeData(
          size: 23,
          color: states.contains(WidgetState.selected)
              ? scheme.primary
              : scheme.onSurfaceVariant,
        ),
      ),
      labelTextStyle: WidgetStateProperty.resolveWith(
        (states) => base.textTheme.labelMedium?.copyWith(
          fontSize: 12,
          fontWeight: states.contains(WidgetState.selected)
              ? FontWeight.w700
              : FontWeight.w400,
          color: states.contains(WidgetState.selected)
              ? scheme.primary
              : scheme.onSurfaceVariant,
        ),
      ),
    ),
    navigationRailTheme: NavigationRailThemeData(
      backgroundColor: scheme.surface,
      indicatorColor: scheme.primaryContainer,
    ),
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      backgroundColor: scheme.primary,
      foregroundColor: scheme.onPrimary,
      elevation: 0,
      highlightElevation: 1,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
    ),
    cardTheme: CardThemeData(
      color: scheme.surfaceContainerLow,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: shape,
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: shape,
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: scheme.primary,
      linearTrackColor: scheme.surfaceContainerHighest,
    ),
  );
}

const moneyFigures = <FontFeature>[FontFeature.tabularFigures()];
